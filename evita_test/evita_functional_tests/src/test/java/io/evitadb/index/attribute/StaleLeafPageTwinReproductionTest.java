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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.OffsetDateTime;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction of a stale leaf-page twin corruption: a persisted `PAGED` {@link InvertedIndex} whose leaf-page
 * list references a STALE leaf page alongside the page that superseded it — two on-disk pages covering
 * overlapping key ranges.
 *
 * Anatomy observed live on a production catalog (a reduced index for a `published` attribute, bucket keys are
 * `Instant`s produced by the `OffsetDateTime -> Instant` filter normalizer):
 *
 * - persisted page seq=29 held 128 buckets `[A .. B]` — a frozen snapshot of a leaf,
 * - persisted page seq=30 held 190 buckets whose first 128 buckets were IDENTICAL to page 29 (same keys,
 *   same record ids) followed by 62 later keys — the same leaf after it kept growing,
 * - the persisted leaf-page list referenced BOTH, so every reload of the catalog assembled a bucket tree
 *   containing the 128-key run twice.
 *
 * {@link InvertedIndex#fromPersistedPages} now validates strict ascending key order both WITHIN each page and
 * ACROSS page boundaries while re-assembling the bucket tree. Any leaf page whose last key does not sort
 * strictly before the first key of its successor page fails fast at load with a
 * {@link GenericEvitaInternalError} whose message contains `overlaps its successor leaf-page sequence`. Because
 * the paged persistence layout has never shipped in a released version, no production catalog can carry such a
 * twin, and the defensive-design rule forbids silently repairing one — so EVERY twin shape fails fast at load
 * rather than assembling a corrupt bucket tree that would violate the index's fundamental invariant (strictly
 * ascending distinct bucket keys) and later crash with a confusing signature far from the cause.
 *
 * The tests below feed the real loader the exact overlapping leaf-page shapes seen (and adjacent boundary
 * shapes) and assert the fail-fast corruption diagnostic is raised at load time:
 *
 * - {@link LoadInvariantTest} — a stale strict-prefix twin next to its superseder,
 * - {@link InterleavedTwinTest} — a post-churn interleaved (non-monotonic cross-page) twin,
 * - {@link BoundaryTwinShapeTest} — boundary twin shapes (equal-content, twin-after-superseder) that must
 *   fail fast just the same.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(ORDER)
@DisplayName("Stale leaf-page twin corruption reproduction")
class StaleLeafPageTwinReproductionTest {

	private static final AttributeIndexKey PUBLISHED_KEY = new AttributeIndexKey(null, "published", null);
	/**
	 * Base timestamp of the generated key space; the concrete value is irrelevant, monotonic spacing matters.
	 */
	private static final Instant BASE = Instant.parse("2026-07-13T11:52:31.000000000Z");
	/**
	 * Number of buckets in the frozen (stale) twin page — mirrors the 128-bucket page size observed in the
	 * production incident this reproduces.
	 */
	private static final int TWIN_PREFIX_SIZE = 128;
	/**
	 * Number of buckets the superseding page grew to before it was persisted — mirrors the 190-bucket page size
	 * the same production leaf grew to.
	 */
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th generated bucket key: {@link #BASE} shifted by `i` milliseconds. Keys are `Instant`s —
	 * the very form the `OffsetDateTime -> Instant` filter normalizer stores in the shared value tree.
	 *
	 * @param i the key ordinal
	 * @return the generated bucket key
	 */
	@Nonnull
	private static Instant key(int i) {
		return BASE.plusMillis(i);
	}

	/**
	 * Returns the record id associated with the `i`-th generated key (stable 1:1 mapping).
	 *
	 * @param i the key ordinal
	 * @return the record id
	 */
	private static int record(int i) {
		return 1_000_000 + i;
	}

	/**
	 * Builds a page of single-record buckets for the key ordinals `[from, to)`.
	 *
	 * @param from the first key ordinal (inclusive)
	 * @param to   the last key ordinal (exclusive)
	 * @return the page buckets in ascending key order
	 */
	@Nonnull
	private static ValueToRecord[] page(int from, int to) {
		final ValueToRecord[] buckets = new ValueToRecord[to - from];
		for (int i = from; i < to; i++) {
			buckets[i - from] = new ValueToRecordPrimitive(key(i), record(i));
		}
		return buckets;
	}

	/**
	 * Loads an {@link InvertedIndex} from the passed persisted pages exactly the way the catalog-open path does
	 * (see `AttributeIndexLoader.loadInvertedIndex` for the `PAGED` shape): page sequences `0..n-1`, the very
	 * normalizer / comparator the production loader re-derives for an `OffsetDateTime` attribute.
	 *
	 * @param pages the persisted leaf pages in list order
	 * @return the rebuilt index
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static InvertedIndex loadFromPersistedPages(@Nonnull ValueToRecord[]... pages) {
		final int[] pageSequences = new int[pages.length];
		for (int i = 0; i < pages.length; i++) {
			pageSequences[i] = i;
		}
		return InvertedIndex.fromPersistedPages(
			OffsetDateTime.class,
			pageSequences,
			pages,
			null,
			pages.length - 1,
			FilterIndex.getNormalizer(OffsetDateTime.class, 0),
			FilterIndex.getComparator(PUBLISHED_KEY, OffsetDateTime.class),
			0
		);
	}

	@Nested
	@DisplayName("Load-side invariant")
	class LoadInvariantTest {

		@Test
		@DisplayName("should fail fast on a stale twin page instead of assembling a corrupt bucket tree")
		void shouldFailFastOnStrictPrefixTwinOnLoad() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					// healthy predecessor page
					page(0, TWIN_PREFIX_SIZE),
					// the frozen STALE twin: an old snapshot of the next leaf
					page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
					// the leaf that superseded it: same 128 buckets plus 62 the leaf gained afterwards
					page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
				),
				"A stale strict-prefix twin must fail fast on reload."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Interleaved (non-monotonic cross-page) twin")
	class InterleavedTwinTest {

		@Test
		@DisplayName("should fail fast on an interleaved twin instead of assembling a corrupt bucket tree")
		void shouldFailFastOnInterleavedTwinOnLoad() {
			// diverged twins: the stale snapshot holds keys the live leaf no longer starts with, so the two pages
			// INTERLEAVE (stale: even ordinals 0..38, live successor: odd ordinals 1..39). Both pages are internally
			// ascending (they pass the within-page bulk-load assertion) yet the cross-page sequence is not monotonic.
			// This is the post-churn stage of the same corruption: after the twin froze, the live leaf lost its
			// leading keys and gained interleaving ones.
			final ValueToRecord[] staleTwin = new ValueToRecord[20];
			final ValueToRecord[] liveSuccessor = new ValueToRecord[20];
			for (int i = 0; i < 20; i++) {
				final int staleOrdinal = 2 * i;
				final int liveOrdinal = 2 * i + 1;
				staleTwin[i] = new ValueToRecordPrimitive(key(staleOrdinal), record(staleOrdinal));
				liveSuccessor[i] = new ValueToRecordPrimitive(key(liveOrdinal), record(liveOrdinal));
			}

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(staleTwin, liveSuccessor),
				"An interleaved (non-monotonic cross-page) twin must fail fast on reload."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Every twin shape fails fast on reload")
	class BoundaryTwinShapeTest {

		@Test
		@DisplayName("should fail fast on an equal-content twin instead of healing it")
		void shouldRefuseEqualContentTwin() {
			// two pages with byte-identical content (same keys, same records, same length): the second page's first
			// key equals the first page's last key, so the cross-leaf boundary is not strictly ascending and the load
			// fails fast — no twin shape is repaired, every overlap is rejected as corruption
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, TWIN_PREFIX_SIZE),
					page(0, TWIN_PREFIX_SIZE)
				),
				"An equal-content twin overlaps its predecessor and must fail fast."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should fail fast on a twin positioned after its superseding page instead of healing it")
		void shouldRefuseTwinAfterSuperseder() {
			// the superseding (longer) page comes FIRST, its stale prefix twin AFTER it: the trailing page's keys
			// overlap the range already covered by the leading page, so the cross-leaf boundary is not strictly
			// ascending and the load fails fast — this overlap shape is rejected as corruption like every other
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, GROWN_PAGE_SIZE),
					page(0, TWIN_PREFIX_SIZE)
				),
				"A twin positioned after its superseder overlaps it and must fail fast."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}
}
