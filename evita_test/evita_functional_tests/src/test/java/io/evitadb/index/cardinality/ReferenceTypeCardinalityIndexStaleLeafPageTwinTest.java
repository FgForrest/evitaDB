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

package io.evitadb.index.cardinality;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that reloading a {@link ReferenceTypeCardinalityIndex} from its persisted leaf pages detects the "stale
 * leaf-page twin" corruption and fails fast: the persisted leaf-page list can end up referencing both a frozen,
 * superseded leaf page and the newer page that replaced it (the twin), with the two pages covering an overlapping run of
 * composed keys. The composed-key → count map is backed by a UNIQUE `long`-keyed bucket tree persisted per leaf page;
 * its spine builder used to assemble such a twin silently — duplicating a run of composed keys in a UNIQUE tree and
 * misrouting later cardinality lookups.
 *
 * The reassembler now validates strict cross-leaf key order, so the reload must reject any twin with a
 * {@link GenericEvitaInternalError} rather than attempting to heal it — regardless of the overlap shape (a provable
 * strict-prefix twin whose keys are an exact prefix of the superseding leaf's keys, or a same-key twin whose cardinality
 * count diverges from its superseder).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("Stale leaf-page twin fails fast on the ReferenceTypeCardinalityIndex reload path")
class ReferenceTypeCardinalityIndexStaleLeafPageTwinTest {

	private static final int TWIN_PREFIX_SIZE = 128;
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th composed key (distinct ascending signed `long`s).
	 *
	 * @param i the key ordinal
	 * @return the composed key
	 */
	private static long key(int i) {
		return 100_000L + i;
	}

	/**
	 * Returns the cardinality count stored at the `i`-th key (distinct, so a diverged count is observable).
	 *
	 * @param i the key ordinal
	 * @return the cardinality count
	 */
	private static long count(int i) {
		return 1L + i;
	}

	/**
	 * Builds the key column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static long[] keyPage(int from, int to) {
		final long[] keys = new long[to - from];
		for (int i = from; i < to; i++) {
			keys[i - from] = key(i);
		}
		return keys;
	}

	/**
	 * Builds the count column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static long[] countPage(int from, int to) {
		final long[] counts = new long[to - from];
		for (int i = from; i < to; i++) {
			counts[i - from] = count(i);
		}
		return counts;
	}

	/**
	 * Loads a {@link ReferenceTypeCardinalityIndex} from the passed persisted pages the way the catalog-open path does
	 * (see `ReferenceTypeCardinalityLoader`): page sequences `0..n-1`, an empty referenced-primary-keys companion.
	 *
	 * @param keyPages   the per-page key columns
	 * @param countPages the per-page count columns, positionally aligned with `keyPages`
	 * @return the rebuilt index
	 */
	@Nonnull
	private static ReferenceTypeCardinalityIndex loadFromPersistedPages(@Nonnull long[][] keyPages, @Nonnull long[][] countPages) {
		final int[] pageSequences = new int[keyPages.length];
		for (int i = 0; i < keyPages.length; i++) {
			pageSequences[i] = i;
		}
		return ReferenceTypeCardinalityIndex.fromPersistedPages(
			"reference `categories`", pageSequences, keyPages, countPages, keyPages.length - 1, Map.of()
		);
	}

	@Nested
	@DisplayName("Strict-prefix twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should throw on a stale strict-prefix twin page instead of silently healing it")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new long[][]{
						keyPage(0, TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						countPage(0, TWIN_PREFIX_SIZE),
						countPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						countPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					}
				),
				"A stale leaf-page twin must fail fast on reload rather than being silently healed."
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
		@DisplayName("should throw on a same-key twin whose cardinality count diverges from its superseder")
		void shouldRefuseDivergedCountTwin() {
			final long[] divergedCounts = countPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE);
			// same composed key as the twin's first key, but a different cardinality count => not a provable strict prefix
			divergedCounts[0] = count(TWIN_PREFIX_SIZE) + 999L;
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new long[][]{
						keyPage(0, TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						countPage(0, TWIN_PREFIX_SIZE),
						countPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						divergedCounts
					}
				),
				"A same-key twin with a diverged cardinality count is an unknown corruption shape and must fail fast."
			);
		}
	}
}
