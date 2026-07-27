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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the `PAGED -> SINGLE` collapse reclaim of {@link FilterIndex} in the WARM-UP shape — two consecutive flushes of
 * the SAME in-memory index with NO intermediate reload.
 *
 * A large filter index persists as one storage-part record per bucket / range B+ tree leaf page (`PAGED`); a small one
 * is carried inline on the root (`SINGLE`). When a `PAGED` axis shrinks enough to fall back to `SINGLE` it must emit a
 * removal for EVERY leaf page it previously wrote: the append-only `OffsetIndex` never reclaims a record that is
 * neither superseded nor explicitly removed, and the collapsed `SINGLE` root references none of them — so a missed
 * removal leaks that page forever.
 *
 * The reclaim must therefore be computed against the set the previous flush left ON DISK
 * ({@link io.evitadb.index.invertedIndex.InvertedIndex#currentLeafPageSequences()} /
 * {@link io.evitadb.index.range.RangeIndex#currentLeafPageSequences()} — the staged-or-published set), NOT against the
 * published live set alone. Publishing only happens at a transactional commit-merge, so in a warming-up catalog nothing
 * ever publishes and a published-set reclaim frees NOTHING.
 *
 * This is the gap a reload-based collapse test cannot see: reassembling an index through the real loader seeds the
 * live-page baseline from disk (`PageStreamRegistry.restoredFrom(...)`), so the published set happens to be correct
 * there and such a test stays green either way. The warm-up shape below never reloads, which is exactly what makes it
 * discriminating. Sibling coverage for the histogram page streams lives in `HistogramIndexLoaderPagingRoundTripTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FilterIndex PAGED -> SINGLE collapse reclaims every prior leaf page across two warm-up flushes")
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class FilterIndexPagedCollapseEmissionTest {
	private static final int ENTITY_INDEX_PK = 7;
	/** > 1 leaf page on both axes, so the index pages out across several leaves (mirrors the histogram suite). */
	private static final int VALUE_COUNT = 3200;
	/** Survivors kept after the `PAGED -> SINGLE` collapse; a handful of values fit inside a single leaf. */
	private static final int COLLAPSE_KEEP = 12;
	private static final AttributeIndexKey BUCKET_ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey RANGE_ATTRIBUTE_KEY = new AttributeIndexKey(null, "validity", null);

	@Nested
	@DisplayName("Bucket axis")
	class BucketAxisCollapse {

		@Test
		@DisplayName("collapsing across two warm-up flushes still removes every prior bucket leaf page")
		void shouldRemovePriorBucketLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
			final OwnerFilterIndex index = pagedBucketSource();

			// first warm-up flush: stages every bucket leaf page, publishes nothing (no commit-merge ever runs)
			final List<StoragePart> firstEmission = emit(index);
			final FilterIndexStoragePart firstRoot = filterRoot(firstEmission);
			assertTrue(firstRoot.isPaged(), "the source filter index must start with a paged bucket axis");
			final int priorLeafPageCount = bucketLeafPages(firstEmission).size();
			assertTrue(priorLeafPageCount >= 3, "the bucket axis must start paged across several leaves");
			assertEquals(
				0, bucketLeafPageRemovals(firstEmission).size(), "a first flush frees no bucket leaf page"
			);

			// collapse the SAME in-memory index: drop all but a handful of values so the survivors fit a single leaf
			for (int i = COLLAPSE_KEEP + 1; i <= VALUE_COUNT; i++) {
				index.removeRecord(i, i);
			}

			final List<StoragePart> secondEmission = emit(index);
			final FilterIndexStoragePart collapsedRoot = filterRoot(secondEmission);
			assertFalse(collapsedRoot.isPaged(), "the collapsed filter index must emit an inline (SINGLE) root");
			assertEquals(
				COLLAPSE_KEEP, collapsedRoot.getHistogramPoints().length,
				"the SINGLE root must carry every surviving bucket inline"
			);
			assertEquals(
				0, bucketLeafPages(secondEmission).size(),
				"a collapsed bucket axis must not re-emit any bucket leaf page"
			);
			assertEquals(
				priorLeafPageCount, bucketLeafPageRemovals(secondEmission).size(),
				"the collapse must remove every bucket leaf page the previous warm-up flush wrote — the append-only " +
					"OffsetIndex never reclaims a record that is neither superseded nor explicitly removed, so a missed " +
					"removal leaks the page forever"
			);
		}
	}

	@Nested
	@DisplayName("Range axis")
	class RangeAxisCollapse {

		@Test
		@DisplayName("collapsing across two warm-up flushes still removes every prior range leaf page")
		void shouldRemovePriorRangeLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
			// a range-typed attribute pages TWO independent streams — the value bucket tree and the RangeIndex threshold
			// tree — each with its own collapse site; both must reclaim against what the previous flush staged
			final OwnerFilterIndex index = pagedRangeSource();

			// first warm-up flush: stages every bucket AND range leaf page, publishes nothing
			final List<StoragePart> firstEmission = emit(index);
			final FilterIndexStoragePart firstRoot = filterRoot(firstEmission);
			assertTrue(firstRoot.isPaged(), "the source filter index must start with a paged bucket axis");
			assertTrue(firstRoot.isRangePaged(), "the source filter index must start with a paged range axis");
			final int priorBucketPageCount = bucketLeafPages(firstEmission).size();
			final int priorRangePageCount = rangeLeafPages(firstEmission).size();
			assertTrue(priorBucketPageCount >= 3, "the bucket axis must start paged across several leaves");
			assertTrue(priorRangePageCount >= 3, "the range axis must start paged across several leaves");
			assertEquals(0, rangeLeafPageRemovals(firstEmission).size(), "a first flush frees no range leaf page");

			// collapse the SAME in-memory index: drop all but a handful of ranges. The survivors' values fit a single
			// bucket leaf and their thresholds a single range leaf, so BOTH axes fall back to the inline SINGLE shape
			// and NEITHER re-enters the paged branch (which would publish the staged set instead of reclaiming it).
			for (int i = COLLAPSE_KEEP + 1; i <= VALUE_COUNT; i++) {
				index.removeRecord(i, IntegerNumberRange.between(i, i + 1));
			}

			final List<StoragePart> secondEmission = emit(index);
			final FilterIndexStoragePart collapsedRoot = filterRoot(secondEmission);
			assertFalse(collapsedRoot.isRangePaged(), "the collapsed range axis must be carried inline (SINGLE)");
			assertFalse(collapsedRoot.isPaged(), "the collapsed bucket axis must be carried inline (SINGLE)");
			assertNotNull(collapsedRoot.getRangeIndex(), "the SINGLE root must carry the surviving range inline");
			assertEquals(
				0, rangeLeafPages(secondEmission).size(),
				"a collapsed range axis must not re-emit any range leaf page"
			);
			assertEquals(
				priorRangePageCount, rangeLeafPageRemovals(secondEmission).size(),
				"the collapse must remove every RANGE leaf page the previous warm-up flush wrote — the append-only " +
					"OffsetIndex never reclaims a record that is neither superseded nor explicitly removed, so a missed " +
					"removal leaks the page forever"
			);
			assertEquals(
				priorBucketPageCount, bucketLeafPageRemovals(secondEmission).size(),
				"the same collapse must reclaim the bucket axis' own prior leaf pages — the two page streams are " +
					"independent and each has to free its own"
			);
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Builds a fresh non-range filter index paged across several bucket leaves (one distinct value per record).
	 *
	 * @return the paged source index; never null
	 */
	@Nonnull
	private static OwnerFilterIndex pagedBucketSource() {
		final OwnerFilterIndex index = new OwnerFilterIndex(BUCKET_ATTRIBUTE_KEY, Integer.class);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			index.addRecord(i, i);
		}
		return index;
	}

	/**
	 * Builds a fresh range-typed filter index paged on BOTH axes: one distinct range per record pages the value bucket
	 * tree, while the ranges' two thresholds each page the {@link io.evitadb.index.range.RangeIndex} threshold tree.
	 * Consecutive ranges deliberately abut (`[i, i+1]`, `[i+1, i+2]`), exactly as the histogram suite's range source.
	 *
	 * @return the paged source index; never null
	 */
	@Nonnull
	private static OwnerFilterIndex pagedRangeSource() {
		final OwnerFilterIndex index = new OwnerFilterIndex(RANGE_ATTRIBUTE_KEY, IntegerNumberRange.class);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			index.addRecord(i, IntegerNumberRange.between(i, i + 1));
		}
		return index;
	}

	/**
	 * Flushes the index and returns every emitted storage part, removals included — the warm-up flush seam.
	 *
	 * @param index the index to flush
	 * @return every part the flush emitted; never null
	 */
	@Nonnull
	private static List<StoragePart> emit(@Nonnull FilterIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Returns the single {@link FilterIndexStoragePart} root of a flush emission.
	 *
	 * @param parts the flush emission
	 * @return the emitted root part; never null
	 */
	@Nonnull
	private static FilterIndexStoragePart filterRoot(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof FilterIndexStoragePart root) {
				return root;
			}
		}
		throw new IllegalStateException("The emission carries no FilterIndexStoragePart root!");
	}

	@Nonnull
	private static List<FilterIndexLeafPagePart> bucketLeafPages(@Nonnull List<StoragePart> parts) {
		final List<FilterIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof FilterIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<FilterIndexLeafPageRemoval> bucketLeafPageRemovals(@Nonnull List<StoragePart> parts) {
		final List<FilterIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof FilterIndexLeafPageRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

	@Nonnull
	private static List<RangeIndexLeafPagePart> rangeLeafPages(@Nonnull List<StoragePart> parts) {
		final List<RangeIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof RangeIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<RangeIndexLeafPageRemoval> rangeLeafPageRemovals(@Nonnull List<StoragePart> parts) {
		final List<RangeIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof RangeIndexLeafPageRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

}
