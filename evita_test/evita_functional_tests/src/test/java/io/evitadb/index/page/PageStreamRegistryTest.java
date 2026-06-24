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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.evitadb.index.page.PageStreamRegistry.NO_PAGE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
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
	 * Builds a mutable live-page set from the given page sequences.
	 *
	 * @param pageSequences the page sequences
	 * @return the assembled set
	 */
	private static Set<Integer> liveSetOf(int... pageSequences) {
		final Set<Integer> set = new HashSet<>();
		for (final int pageSequence : pageSequences) {
			set.add(pageSequence);
		}
		return set;
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
			registry.stage(1, liveSetOf(0, 2));
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
			registry.stage(1, liveSetOf(0));

			assertTrue(registry.hasStaged(1), "Stream must report a pending staged live set.");
			assertTrue(registry.livePages(1).isEmpty(), "Staged page must not be visible before publish.");

			registry.publishStaged();
			assertFalse(registry.hasStaged(1), "Publishing must clear the pending flag.");
			assertEquals(Set.of(0), registry.livePages(1), "Published live set must match the staged set.");
		}

		@Test
		@DisplayName("discards a staged live set on abort, leaving the live set and high-water intact")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldDiscardStagedLiveSetOnAbort() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // 0
			registry.stage(1, liveSetOf(0));
			registry.publishStaged(); // first commit lands page 0

			// a second flush stages a change and allocates a page, then the commit aborts
			registry.allocate(1); // 1
			registry.stage(1, liveSetOf(0, 1));
			registry.discardStaged();

			assertFalse(registry.hasStaged(1), "Discard must clear the pending flag.");
			assertTrue(registry.livePages(1).contains(0), "Live set must survive an aborted commit.");
			assertFalse(registry.livePages(1).contains(1), "The aborted page must not enter the live set.");
			assertEquals(1, registry.highWater(1), "High-water must not roll back on abort (advance-only).");
		}

		@Test
		@DisplayName("replaces a pending staged live set when staged again before publishing")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldReplacePendingStagedLiveSet() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1); // 0
			registry.allocate(1); // 1
			registry.stage(1, liveSetOf(0, 1));
			registry.stage(1, liveSetOf(0));
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
			registry.stage(1, liveSetOf(0));
			registry.stage(2, liveSetOf(0));

			registry.publishStaged();
			assertEquals(Set.of(0), registry.livePages(1));
			assertEquals(Set.of(0), registry.livePages(2));
		}

		@Test
		@DisplayName("treats publish and discard as no-ops when nothing is staged")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldTreatEmptyHandshakeAsNoOp() {
			final PageStreamRegistry registry = new PageStreamRegistry();
			registry.allocate(1);
			registry.publishStaged();
			registry.discardStaged();
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
				() -> registry.stage(1, liveSetOf(5)),
				"Staging an unallocated page sequence must fail."
			);
		}
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
			registry.restore(1, 4, liveSetOf(0, 2, 4));

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
			registry.restore(1, NO_PAGE, liveSetOf());
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
				() -> registry.restore(1, 2, liveSetOf(3)),
				"A live page above the high-water is impossible and must fail."
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
			registry.stage(1, liveSetOf(0));
			registry.publishStaged();

			registry.forget(1);
			assertFalse(registry.isKnown(1));
			assertEquals(NO_PAGE, registry.highWater(1));
			assertTrue(registry.livePages(1).isEmpty());
			assertEquals(0, registry.allocate(1), "A forgotten stream re-allocates from zero.");
		}
	}
}
