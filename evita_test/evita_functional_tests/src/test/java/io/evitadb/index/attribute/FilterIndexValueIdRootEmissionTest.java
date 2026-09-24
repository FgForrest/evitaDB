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
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a {@link FilterIndex} carries the shared value tree's **value ids** onto disk: the id column on every
 * emitted leaf page, the inline id column of a `SINGLE` root, and — the load-bearing one — the rule that the value id
 * high-water mark forces the root out even when neither axis's page list moved.
 *
 * # Why the high-water rule needs its own test
 *
 * A `PAGED` root is otherwise skipped whenever both page lists are stable, which is the steady state of almost every
 * commit. But a commit can mint ids into an *existing* leaf without ever allocating or freeing a page, and the root is
 * the only place the high-water mark is written. Skip it there and the mark never reaches disk, so a restart re-mints
 * ids the leaf pages already carry and two live values end up wearing one id.
 *
 * These are warm-up flushes of the same in-memory index with no intermediate reload, mirroring
 * {@link FilterIndexPagedCollapseEmissionTest} — the shape in which a root skip is actually decided.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Value ids on the filter index's persisted root and leaf pages")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(STORAGE)
class FilterIndexValueIdRootEmissionTest {

	private static final int ENTITY_INDEX_PK = 7;
	/** Distinct values of a paged fixture — well past the 256-bucket leaf block, so the tree spans several leaves. */
	private static final int PAGED_VALUE_COUNT = 1_000;
	/** Distinct values of an inline fixture — comfortably inside one leaf block, so the index stays `SINGLE`. */
	private static final int INLINE_VALUE_COUNT = 10;
	/** Record ids used by writes made after the first flush, kept clear of the seeded ones. */
	private static final int LATE_RECORD_ID = 1_000_000;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	private static final String TEST_CONSUMER = "filter-index-value-id-test";

	/**
	 * Builds an id-carrying filter index over `valueCount` distinct EVEN values, one record each.
	 *
	 * The consumer is registered while the tree is still empty — the only moment ids may be switched on — and the
	 * values are even so that a later insert can land an odd one *between* two existing buckets rather than appending
	 * past the end.
	 *
	 * @param valueCount how many distinct values to seed
	 * @return the seeded, id-carrying index
	 */
	@Nonnull
	private static OwnerFilterIndex idCarryingIndex(int valueCount) {
		final OwnerFilterIndex index = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
		index.getInvertedIndex().attachValueIdConsumer(TEST_CONSUMER);
		for (int i = 1; i <= valueCount; i++) {
			index.addRecord(i, 2 * i);
		}
		return index;
	}

	/**
	 * Flushes the index and returns every emitted storage part — the warm-up flush seam.
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
	 * Returns the {@link FilterIndexStoragePart} root of a flush emission, or `null` when the flush skipped it.
	 *
	 * @param parts the flush emission
	 * @return the emitted root part, or `null` when none was emitted
	 */
	@Nullable
	private static FilterIndexStoragePart filterRoot(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof FilterIndexStoragePart root) {
				return root;
			}
		}
		return null;
	}

	/**
	 * Returns every bucket leaf page of a flush emission, in emission order.
	 *
	 * @param parts the flush emission
	 * @return the emitted leaf pages; never null
	 */
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

	@Nested
	@DisplayName("The paged root")
	class PagedRoot {

		@Test
		@DisplayName("a mark that advanced without any page moving still forces the root out")
		void shouldReEmitRootWhenValueIdHighWaterAdvancedWithoutAnyPageListChange() {
			final OwnerFilterIndex index = idCarryingIndex(PAGED_VALUE_COUNT);
			final FilterIndexStoragePart firstRoot = filterRoot(emit(index));
			assertNotNull(firstRoot, "a first flush must write the root");
			assertTrue(firstRoot.isPaged(), "the fixture must page out across several leaves");

			// one new distinct value between two existing buckets: it mints an id, and it lands inside a leaf that
			// already exists rather than allocating a new page
			index.addRecord(LATE_RECORD_ID, 501);

			final FilterIndexStoragePart secondRoot = filterRoot(emit(index));

			assertNotNull(secondRoot, "the advanced high-water mark must force the root out");
			assertEquals(
				index.getInvertedIndex().getNextValueId(), secondRoot.getNextValueId(),
				"the re-emitted root must carry the mark the tree currently stands at"
			);
			// the calibration: with the page list unchanged, the mark is the ONLY thing that could have forced the
			// root out. Without this the test would pass for the wrong reason on any fixture where the insert
			// happened to split a leaf
			assertArrayEquals(
				firstRoot.getLeafPageSequences(), secondRoot.getLeafPageSequences(),
				"the insert must not have moved the page list, or the root re-emit proves nothing"
			);
		}

		@Test
		@DisplayName("the baseline-capture pass that repeats the collect leaves the mark where it stands")
		void shouldNotDisturbValueIdHighWaterWhenBaselineCaptureRepeatsTheCollect() {
			// Every real flush collects TWICE. `DataStoreChanges#popTrappedUpdates` takes the parts, and then
			// `EntityIndex#notifyFlushed` -> `captureOriginalsFromComponents` runs the very same collect again into a
			// sink it throws away. The mark is advanced by the collect that actually emits the root, so this second
			// pass must find nothing left to do - were it the other way round, the mark's advance would ride on a
			// pass whose output nobody writes, and the persisted root would lag the ids already in the leaf pages
			final OwnerFilterIndex index = idCarryingIndex(PAGED_VALUE_COUNT);
			final FilterIndexStoragePart firstRoot = filterRoot(emit(index));
			assertNotNull(firstRoot, "a first flush must write the root");

			final List<StoragePart> capturePass = emit(index);

			assertNull(filterRoot(capturePass), "the baseline-capture re-run must not emit a second root");
			assertFalse(
				index.getInvertedIndex().isValueIdHighWaterDirty(),
				"the mark the first collect emitted must still stand after the capture pass has run"
			);
		}

		@Test
		@DisplayName("a commit that mints no id and moves no page leaves the root on disk untouched")
		void shouldSkipRootWhenNothingButRecordsChanged() {
			final OwnerFilterIndex index = idCarryingIndex(PAGED_VALUE_COUNT);
			emit(index);
			index.addRecord(LATE_RECORD_ID, 501);
			emit(index);

			// a record joining an EXISTING value mints nothing, so the mark stands still and the root is byte-identical
			// to what the previous flush wrote
			index.addRecord(LATE_RECORD_ID + 1, 500);
			final List<StoragePart> thirdEmission = emit(index);

			assertNull(filterRoot(thirdEmission), "a stable mark and a stable page list must leave the root alone");
			// and the flush really did emit something: a flush that produced nothing at all would satisfy the
			// assertion above vacuously
			assertFalse(
				bucketLeafPages(thirdEmission).isEmpty(),
				"the leaf that took the new record must still have been written"
			);
		}

		@Test
		@DisplayName("every emitted leaf page carries one id per bucket")
		void shouldCarryValueIdColumnOnEveryEmittedLeafPage() {
			final OwnerFilterIndex index = idCarryingIndex(PAGED_VALUE_COUNT);

			final List<FilterIndexLeafPagePart> leafPages = bucketLeafPages(emit(index));

			assertFalse(leafPages.isEmpty(), "a paged fixture must emit leaf pages");
			for (final FilterIndexLeafPagePart leafPage : leafPages) {
				// all-or-nothing across a generation is what the loader refuses violations of; this is the writer side
				// of that contract, and a single page emitted without its column would make the whole index unloadable
				assertNotNull(
					leafPage.getValueIds(),
					"page " + leafPage.getPageSequence() + " was written without its id column"
				);
				assertEquals(
					leafPage.getBuckets().length, leafPage.getValueIds().length,
					"page " + leafPage.getPageSequence() + " carries an id column that does not match its buckets"
				);
			}
		}
	}

	@Nested
	@DisplayName("The inline root")
	class InlineRoot {

		@Test
		@DisplayName("the inline id column lines up with the inline buckets, entry for entry")
		void shouldCarryInlineValueIdColumnAlignedWithTheInlineBuckets() {
			final OwnerFilterIndex index = idCarryingIndex(INLINE_VALUE_COUNT);
			assertFalse(index.getInvertedIndex().isPaged(), "the fixture must stay inside a single leaf");

			final FilterIndexStoragePart root = filterRoot(emit(index));

			assertNotNull(root, "a SINGLE root always rides the flush");
			final int[] inlineValueIds = root.getInlineValueIds();
			assertNotNull(inlineValueIds, "an id-carrying inline index must write its id column");
			final ValueToRecordBitmap[] histogramPoints = root.getHistogramPoints();
			assertEquals(histogramPoints.length, inlineValueIds.length);
			// the buckets and the ids are collected by two separate walks of the same tree, and nothing else pins that
			// the two walks agree on the order - a divergence would misattribute every value on the next reload
			for (int i = 0; i < histogramPoints.length; i++) {
				assertEquals(
					index.getInvertedIndex().getValueId(histogramPoints[i].getValue()), inlineValueIds[i],
					"inline id " + i + " does not name the bucket it sits beside"
				);
			}
		}

		@Test
		@DisplayName("an index nobody asked ids of writes none at all")
		void shouldCarryNoValueIdsWhenNoConsumerRegistered() {
			// the common case, and the one that must stay free: almost every tree in a catalog has no consumer
			final OwnerFilterIndex index = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
			for (int i = 1; i <= INLINE_VALUE_COUNT; i++) {
				index.addRecord(i, 2 * i);
			}

			final FilterIndexStoragePart root = filterRoot(emit(index));

			assertNotNull(root, "a SINGLE root always rides the flush");
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, root.getNextValueId());
			assertNull(root.getInlineValueIds());
		}
	}
}
