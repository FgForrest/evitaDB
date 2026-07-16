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

package io.evitadb.index.range;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the stale leaf-page twin corruption on the {@link RangeIndex} paged restore path. A `PAGED` range index
 * persists one leaf page per B+ tree leaf and a root part listing the ordered leaf-page sequences;
 * {@link RangeIndex#fromPersistedPages} re-assembles one in-memory leaf per persisted page. Its
 * {@code TransactionalLongBPlusTree.assembleFromSingleLeafTrees} spine builder takes each leaf's left boundary
 * threshold as a separator — so a frozen stale leaf referenced alongside the page that superseded it (the twin) would
 * duplicate a run of thresholds and their starts/ends bitmaps if it were loaded silently.
 *
 * The reassembler now validates strict cross-leaf key order: the reload must reject any twin (or any other overlapping
 * leaf-page sequence) with a {@link GenericEvitaInternalError} rather than loading silently corrupted state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@DisplayName("Stale leaf-page twin fails fast on the RangeIndex reload path")
class RangeIndexStaleLeafPageTwinTest {

	/**
	 * Threshold base of the synthesized key space; the concrete values are irrelevant, monotonic spacing matters.
	 */
	private static final long BASE = 1_000L;
	/**
	 * Number of range points in the frozen (stale) twin page — sized well past a trivial one- or two-point page so the
	 * strict-prefix comparison exercises a realistic multi-hundred-entry leaf.
	 */
	private static final int TWIN_PREFIX_SIZE = 128;
	/**
	 * Number of range points the superseding page grew to before it was persisted — strictly larger than
	 * {@link #TWIN_PREFIX_SIZE} so the twin is a genuine (non-trivial) strict prefix of it.
	 */
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th generated threshold: {@link #BASE} shifted by `i`.
	 *
	 * @param i the threshold ordinal
	 * @return the generated threshold
	 */
	private static long threshold(int i) {
		return BASE + i;
	}

	/**
	 * Builds a range point at the `i`-th threshold with deterministic single-record starts/ends bitmaps, so a twin
	 * prefix is byte-identical to the superseding page's prefix.
	 *
	 * @param i the threshold ordinal
	 * @return the range point
	 */
	@Nonnull
	private static TransactionalRangePoint point(int i) {
		return new TransactionalRangePoint(threshold(i), new int[]{500_000 + i}, new int[]{900_000 + i});
	}

	/**
	 * Builds a page of range points for the threshold ordinals `[from, to)`.
	 *
	 * @param from the first ordinal (inclusive)
	 * @param to   the last ordinal (exclusive)
	 * @return the page points in ascending threshold order
	 */
	@Nonnull
	private static TransactionalRangePoint[] page(int from, int to) {
		final TransactionalRangePoint[] points = new TransactionalRangePoint[to - from];
		for (int i = from; i < to; i++) {
			points[i - from] = point(i);
		}
		return points;
	}

	/**
	 * Loads a {@link RangeIndex} from the passed persisted pages exactly the way the catalog-open path does
	 * (see `AttributeIndexLoader.loadRangeIndex`): page sequences `0..n-1`.
	 *
	 * @param pages the persisted leaf pages in list order
	 * @return the rebuilt index
	 */
	@Nonnull
	private static RangeIndex loadFromPersistedPages(@Nonnull TransactionalRangePoint[]... pages) {
		final int[] pageSequences = new int[pages.length];
		for (int i = 0; i < pages.length; i++) {
			pageSequences[i] = i;
		}
		return RangeIndex.fromPersistedPages("attribute `published`", pageSequences, pages, pages.length - 1);
	}

	@Nested
	@DisplayName("Any twin fails fast on reload")
	class FailFastTest {

		@Test
		@DisplayName("should fail fast on a stale strict-prefix twin page")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					// healthy predecessor page
					page(0, TWIN_PREFIX_SIZE),
					// the frozen STALE twin: an old snapshot of the next leaf (page sequence 1)
					page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
					// the leaf that superseded it: same 128 points plus 62 the leaf gained afterwards (page sequence 2)
					page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
				),
				"A stale strict-prefix leaf-page twin must fail fast on reload instead of loading silently."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should fail fast on a sentinel-bearing stale first-page twin")
		void shouldThrowOnSentinelBearingFirstPageTwin() {
			// the stale twin and the grown first leaf both carry the MIN_VALUE border sentinel at position 0
			final TransactionalRangePoint[] staleFirst = new TransactionalRangePoint[1 + 64];
			final TransactionalRangePoint[] grownFirst = new TransactionalRangePoint[1 + TWIN_PREFIX_SIZE];
			staleFirst[0] = new TransactionalRangePoint(Long.MIN_VALUE);
			grownFirst[0] = new TransactionalRangePoint(Long.MIN_VALUE);
			for (int i = 0; i < 64; i++) {
				staleFirst[1 + i] = point(i);
			}
			for (int i = 0; i < TWIN_PREFIX_SIZE; i++) {
				grownFirst[1 + i] = point(i);
			}
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(staleFirst, grownFirst),
				"A sentinel-bearing stale first-page twin must fail fast on reload instead of loading silently."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Unhealable overlap fails fast")
	class HardFailureTest {

		@Test
		@DisplayName("should throw on a same-threshold twin whose starts bitmap diverges from its superseder")
		void shouldRefuseDivergedPayloadTwin() {
			final TransactionalRangePoint[] divergedSuperseder = page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE);
			// same threshold as the twin's first point, but a different starts bitmap => not a provable strict prefix
			divergedSuperseder[0] = new TransactionalRangePoint(
				threshold(TWIN_PREFIX_SIZE), new int[]{123_456_789}, new int[]{900_000 + TWIN_PREFIX_SIZE}
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, TWIN_PREFIX_SIZE),
					page(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
					divergedSuperseder
				),
				"A same-threshold twin with a diverged payload is an unknown corruption shape and must fail fast."
			);
		}
	}
}
