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

package io.evitadb.index.page;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.PagedLeafHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.evitadb.index.page.PageStreamRegistry.NO_PAGE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PageStreamRegistry} — the per-stream page-sequence allocator, explicit high-water, and live-page set
 * backing the granular index page layout (the set serves the freed-page reclaim; per-leaf change detection lives on the
 * leaf's dirty flag, not here).
 * Exercises advance-only never-reused allocation, the explicit high-water (including across freed pages), per-stream
 * isolation, the stage → publish-on-commit / discard-on-abort live-set handshake, cold-load restore, and the premise
 * guards.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Page stream registry")
@Tag(INDEXING)
@Tag(SERIALIZATION)
class PageStreamRegistryTest {

	/**
	 * Builds a live-page list from the given page sequences, in the order they are passed.
	 *
	 * @param pageSequences the page sequences in ascending key order
	 * @return the same page sequences as an ordered {@code int[]} live-page list
	 */
	private static int[] livePagesOf(int... pageSequences) {
		return pageSequences;
	}

	@Nested
	@DisplayName("Allocation and high-water")
	class Allocation {

		@Test
		@DisplayName("allocates a dense ascending sequence from zero, advancing the high-water")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldAllocateAscendingSequence() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			assertEquals(NO_PAGE, registry.highWater(1), "Unknown stream must report NO_PAGE.");
			assertFalse(registry.isKnown(1), "Unknown stream must not be known.");

			assertEquals(0, registry.allocate(1));
			assertEquals(1, registry.allocate(1));
			assertEquals(2, registry.allocate(1));
			assertEquals(2, registry.highWater(1), "High-water must track the largest allocation.");
			assertTrue(registry.isKnown(1), "Allocated stream must be known.");
		}

		@Test
		@DisplayName("never reuses a page sequence even after the page is freed from the live set")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldNeverReuseAFreedPageSequence() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // 0
			registry.allocate(1); // 1
			registry.allocate(1); // 2
			// page 1 is freed: the next published live set simply omits it
			registry.stage(1, livePagesOf(0, 2));
			registry.publishStaged();

			assertFalse(registry.livePages(1).contains(1), "Freed page must drop out of the live set.");
			assertEquals(2, registry.highWater(1), "High-water must not retreat when a page is freed.");
			assertEquals(3, registry.allocate(1), "A freed id must never be handed out again.");
		}

		@Test
		@DisplayName("allocates independently per stream")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldAllocateIndependentlyPerStream() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			assertEquals(0, registry.allocate(1));
			assertEquals(0, registry.allocate(2));
			assertEquals(1, registry.allocate(1));
			assertEquals(1, registry.allocate(2));
			assertEquals(1, registry.highWater(1));
			assertEquals(1, registry.highWater(2));
		}
	}

	@Nested
	@DisplayName("Live-set staging handshake")
	class Staging {

		@Test
		@DisplayName("keeps a staged live set invisible until it is published")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldHideStagedLiveSetUntilPublished() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // 0
			registry.stage(1, livePagesOf(0));

			assertTrue(registry.hasStaged(1), "Stream must report a pending staged live set.");
			assertTrue(registry.livePages(1).isEmpty(), "Staged page must not be visible before publish.");

			registry.publishStaged();
			assertFalse(registry.hasStaged(1), "Publishing must clear the pending flag.");
			assertEquals(Set.of(0), registry.livePages(1), "Published live set must match the staged set.");
		}

		@Test
		@DisplayName("replaces a pending staged live set when staged again before publishing")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldReplacePendingStagedLiveSet() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // 0
			registry.allocate(1); // 1
			registry.stage(1, livePagesOf(0, 1));
			registry.stage(1, livePagesOf(0));
			registry.publishStaged();

			assertEquals(Set.of(0), registry.livePages(1), "The last staged set must win.");
		}

		@Test
		@DisplayName("publishes and discards across multiple streams without crosstalk")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldHandleStagingAcrossStreams() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // stream 1, page 0
			registry.allocate(2); // stream 2, page 0
			registry.stage(1, livePagesOf(0));
			registry.stage(2, livePagesOf(0));

			registry.publishStaged();
			assertEquals(Set.of(0), registry.livePages(1));
			assertEquals(Set.of(0), registry.livePages(2));
		}

		@Test
		@DisplayName("treats publish as a no-op when nothing is staged")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldTreatEmptyHandshakeAsNoOp() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1);
			registry.publishStaged();
			assertTrue(registry.livePages(1).isEmpty(), "No staging means the live set stays empty.");
			assertEquals(0, registry.highWater(1), "Empty handshake must not touch the high-water.");
		}

		@Test
		@DisplayName("rejects staging a page beyond the stream's high-water")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldRejectStagingBeyondHighWater() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // high-water 0
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.stage(1, livePagesOf(5)),
				"Staging an unallocated page sequence must fail."
			);
		}

		@Test
		@DisplayName("reports the staged set for a collapse reclaim before any publish, then the published set after")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldReportPendingLiveSetFromStagedSetBeforePublish() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			assertArrayEquals(
				new int[0], registry.pendingLivePageSequences(1),
				"An unknown stream must report no pending live pages."
			);

			registry.allocate(1); // 0
			registry.allocate(1); // 1
			registry.stage(1, livePagesOf(0, 1));

			// a PAGED -> SINGLE collapse reclaims against what THIS flush staged, before it publishes: the published
			// live set still lags a whole flush behind (empty here), so only the staged set names the pages to remove
			assertTrue(registry.livePages(1).isEmpty(), "The staged set must stay invisible to the published live set.");
			assertArrayEquals(
				new int[]{0, 1}, sorted(registry.pendingLivePageSequences(1)),
				"Before publish, the pending live set must be the staged set, not the lagging published set."
			);

			registry.publishStaged();
			assertArrayEquals(
				new int[]{0, 1}, sorted(registry.pendingLivePageSequences(1)),
				"After publish, the pending live set must fall back to the now-current published set."
			);
		}
	}

	/**
	 * Sorts a page-sequence array in place and returns it, so assertions can ignore the {@link java.util.HashSet}
	 * iteration order behind {@link PageStreamRegistry#pendingLivePageSequences(int)}.
	 *
	 * @param pageSequences the page sequences to sort
	 * @return the same array, sorted ascending
	 */
	private static int[] sorted(int[] pageSequences) {
		Arrays.sort(pageSequences);
		return pageSequences;
	}

	@Nested
	@DisplayName("Cold-load restore")
	class Restore {

		@Test
		@DisplayName("seeds high-water and live set, then continues allocating past the high-water")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldRestoreAndContinue() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.restore(1, 4, livePagesOf(0, 2, 4));

			assertTrue(registry.isKnown(1));
			assertEquals(4, registry.highWater(1));
			assertTrue(registry.livePages(1).contains(0));
			assertTrue(registry.livePages(1).contains(2));
			assertFalse(registry.livePages(1).contains(1), "A page absent from the restored live set must not be live.");
			assertEquals(5, registry.allocate(1), "Allocation must continue past the restored high-water.");
		}

		@Test
		@DisplayName("restores an empty stream with no pages")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldRestoreEmptyStream() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.restore(1, NO_PAGE, livePagesOf());
			assertEquals(NO_PAGE, registry.highWater(1));
			assertEquals(0, registry.allocate(1), "First allocation after an empty restore must be zero.");
		}

		@Test
		@DisplayName("rejects a restored live-set page beyond the high-water")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldRejectRestoredLiveSetBeyondHighWater() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.restore(1, 2, livePagesOf(3)),
				"A live page above the high-water is impossible and must fail."
			);
		}
	}

	/**
	 * Minimal {@link PagedLeafHandle} double: a leaf carrying a page sequence and a dirty flag, with no tree behind it.
	 */
	private static final class TestLeafHandle implements PagedLeafHandle {
		private int pageSequence;
		private boolean dirty;

		private TestLeafHandle(int pageSequence, boolean dirty) {
			this.pageSequence = pageSequence;
			this.dirty = dirty;
		}

		@Override
		public int getPageSequence() {
			return this.pageSequence;
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.pageSequence = pageSequence;
		}

		@Override
		public boolean isDirty() {
			return this.dirty;
		}

		@Override
		public void clearDirty() {
			this.dirty = false;
		}
	}

	@Nested
	@DisplayName("Page-list change detection")
	class ChangeDetection {

		@Test
		@DisplayName("throws when the published baseline disagrees with the collected page list")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldThrowWhenPublishedBaselineDesyncsFromCollectedPageList() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			// a previous flush allocated pages 0 and 1 and wrote them to disk, but never published its staged set —
			// the exact warm-up baseline desync: the pages are on disk while the live set still claims the stream is empty
			registry.allocate(1); // 0
			registry.allocate(1); // 1
			registry.stage(1, livePagesOf(0, 1));

			// the next flush finds a merged tree: page 1 was merged away and page 0 absorbed its keys. Against the stale
			// (empty) baseline the freed-page proxy computes `∅ - {0} = ∅` and reports "page list unchanged" — which would
			// skip the root and leave it listing the dropped page 1. The direct comparison sees [0] != [] and disagrees:
			// a disagreement is only possible when a baseline is wrong, so it must surface here rather than on disk.
			final List<TestLeafHandle> handles = List.of(new TestLeafHandle(0, true));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.collectChangedPages(1, handles, (pageSequence, handle) -> pageSequence),
				"A page list that disagrees with the published baseline must fail the flush, never reach storage."
			);
		}

		@Test
		@DisplayName("agrees with the freed-page proxy across a merge, a split and an untouched flush")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldAgreeWithProxyAcrossMergeSplitAndUntouchedFlush() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.restore(1, 1, livePagesOf(0, 1));

			// an untouched flush re-collects the same pages: nothing allocated, nothing freed, list identical
			assertFalse(
				registry.collectChangedPages(
					1,
					List.of(new TestLeafHandle(0, false), new TestLeafHandle(1, false)),
					(pageSequence, handle) -> pageSequence
				).pageListChanged(),
				"An untouched flush must not re-emit the root."
			);
			registry.publishStaged();

			// a split adds a fresh leaf: the allocator stamps it and the list grows
			assertTrue(
				registry.collectChangedPages(
					1,
					List.of(
						new TestLeafHandle(0, true),
						new TestLeafHandle(PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE, true),
						new TestLeafHandle(1, false)
					),
					(pageSequence, handle) -> pageSequence
				).pageListChanged(),
				"A split must re-emit the root."
			);
			registry.publishStaged();

			// a merge drops page 1: nothing is allocated, so only the freed page makes the list change
			assertTrue(
				registry.collectChangedPages(
					1,
					List.of(new TestLeafHandle(0, true), new TestLeafHandle(2, false)),
					(pageSequence, handle) -> pageSequence
				).pageListChanged(),
				"A merge must re-emit the root."
			);
		}
	}

	@Nested
	@DisplayName("Stream lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("forgets a stream, discarding its allocator, high-water and live set")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldForgetStream() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1);
			registry.stage(1, livePagesOf(0));
			registry.publishStaged();

			registry.forget(1);
			assertFalse(registry.isKnown(1));
			assertEquals(NO_PAGE, registry.highWater(1));
			assertTrue(registry.livePages(1).isEmpty());
			assertEquals(0, registry.allocate(1), "A forgotten stream re-allocates from zero.");
		}
	}
}
