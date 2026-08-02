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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the senesi stale-leaf-page-twin reproduction (`documentation/adr/2026-07-18-paged-index-corruption-and-flush-failure-boundary/`) to the
 * {@link OwnerUniqueIndex} paged restore path. The value → owning-record-id map is a UNIQUE bucket tree persisted per
 * leaf page; a frozen stale leaf referenced alongside the page that superseded it (the twin) duplicates a value run in a
 * UNIQUE tree and misroutes equality probes once the twins diverge. Its {@code assembleFromSingleLeafTrees} spine
 * builder validates strict cross-leaf key order, so any such twin is detected at reassembly.
 *
 * The reload must reject any twin with {@link GenericEvitaInternalError} — there is no healing path; every detected
 * overlap shape fails fast at {@link OwnerUniqueIndex#fromPersistedPages}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Stale leaf-page twin fails fast on the OwnerUniqueIndex reload path")
class OwnerUniqueIndexStaleLeafPageTwinTest {

	private static final String ENTITY_TYPE = "product";
	private static final AttributeIndexKey CODE_KEY = new AttributeIndexKey(null, "code", null);
	private static final int TWIN_PREFIX_SIZE = 128;
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th unique attribute value (distinct ascending integers).
	 */
	private static Integer value(int i) {
		return i;
	}

	/**
	 * Returns the record id owning the `i`-th value (stable 1:1 mapping).
	 */
	private static int record(int i) {
		return 500_000 + i;
	}

	/**
	 * Builds the value column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static Serializable[] valuePage(int from, int to) {
		final Serializable[] values = new Serializable[to - from];
		for (int i = from; i < to; i++) {
			values[i - from] = value(i);
		}
		return values;
	}

	/**
	 * Builds the record-id column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static int[] recordPage(int from, int to) {
		final int[] records = new int[to - from];
		for (int i = from; i < to; i++) {
			records[i - from] = record(i);
		}
		return records;
	}

	/**
	 * Loads an {@link OwnerUniqueIndex} from the passed persisted pages the way the catalog-open path does
	 * (see `AttributeIndexLoader`): page sequences `0..n-1`, an `Integer` attribute type.
	 *
	 * @param valuePages  the per-page value columns
	 * @param recordPages the per-page record-id columns, positionally aligned with `valuePages`
	 * @return the rebuilt index
	 */
	@Nonnull
	private static OwnerUniqueIndex loadFromPersistedPages(@Nonnull Serializable[][] valuePages, @Nonnull int[][] recordPages) {
		final int[] pageSequences = new int[valuePages.length];
		for (int i = 0; i < valuePages.length; i++) {
			pageSequences[i] = i;
		}
		return OwnerUniqueIndex.fromPersistedPages(
			ENTITY_TYPE, CODE_KEY, Integer.class, pageSequences, valuePages, recordPages, valuePages.length - 1
		);
	}

	@Nested
	@DisplayName("Strict-prefix twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should throw when a stale strict-prefix twin page is present on reload")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new Serializable[][]{
						valuePage(0, TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new int[][]{
						recordPage(0, TWIN_PREFIX_SIZE),
						recordPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						recordPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					}
				),
				"A stale leaf-page twin must fail fast on reload instead of loading silently."
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
		@DisplayName("should throw on a same-value twin pointing at a different record id than its superseder")
		void shouldRefuseDivergedRecordTwin() {
			final int[] divergedRecords = recordPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE);
			// same value as the twin's first value, but a different owning record id => not a provable strict prefix
			divergedRecords[0] = record(TWIN_PREFIX_SIZE) + 987_654;
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new Serializable[][]{
						valuePage(0, TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new int[][]{
						recordPage(0, TWIN_PREFIX_SIZE),
						recordPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						divergedRecords
					}
				),
				"A same-value twin pointing at a different record id is an unknown corruption shape and must fail fast."
			);
		}
	}
}
