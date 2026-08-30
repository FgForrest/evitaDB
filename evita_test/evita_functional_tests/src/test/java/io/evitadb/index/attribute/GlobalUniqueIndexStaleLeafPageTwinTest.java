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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the production-catalog stale-leaf-page-twin reproduction (`documentation/adr/2026-07-18-paged-index-corruption-and-flush-failure-boundary/`) to the
 * catalog-level {@link GlobalUniqueIndex} paged restore path. The value → packed-`(entityType, pk, locale)` map is a
 * UNIQUE bucket tree persisted per leaf page; a frozen stale leaf referenced alongside the page that superseded it
 * (the twin) breaks the strict ascending cross-leaf key order the {@code fromPersistedPages} spine builder now
 * enforces — a corruption whose catalog-wide blast radius (e.g. globally-unique `url` routing lookups miss) used to
 * load silently before the assembler validated leaf order.
 *
 * The reload must reject any twin with {@link GenericEvitaInternalError}: the B+ tree assembler validates strict
 * cross-leaf key order, so any detected twin — a provable strict-prefix twin or any other overlap shape (e.g. a
 * same-value twin pointing at a different packed payload) — fails fast at `GlobalUniqueIndex.fromPersistedPages`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Stale leaf-page twin fail-fast rejection on the GlobalUniqueIndex reload path")
class GlobalUniqueIndexStaleLeafPageTwinTest {

	private static final AttributeKey URL_KEY = new AttributeKey("url");
	private static final int TWIN_PREFIX_SIZE = 128;
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th unique attribute value (distinct ascending integers).
	 */
	private static Integer value(int i) {
		return i;
	}

	/**
	 * Returns the packed `long` payload owning the `i`-th value: entity type id 0, entity primary key `700000+i`, and the
	 * `NO_LOCALE` sentinel — the same `high16 | mid16 | low32` layout `GlobalUniqueIndex.packTuple` produces.
	 */
	private static long payload(int i) {
		// high16 == 0 encodes the NO_LOCALE(-1) sentinel (biased to 0), mid16 == 0 the entity type id, low32 the pk
		return NumberUtils.pack(0, 0, 700_000 + i);
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
	 * Builds the packed-payload column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static long[] payloadPage(int from, int to) {
		final long[] payloads = new long[to - from];
		for (int i = from; i < to; i++) {
			payloads[i - from] = payload(i);
		}
		return payloads;
	}

	/**
	 * Loads a {@link GlobalUniqueIndex} from the passed persisted pages the way the catalog-open path does (see
	 * `DefaultCatalogPersistenceService`): page sequences `0..n-1`, an `Integer` attribute type, no locales.
	 *
	 * @param valuePages   the per-page value columns
	 * @param payloadPages the per-page packed-payload columns, positionally aligned with `valuePages`
	 * @return the rebuilt index
	 */
	@Nonnull
	private static GlobalUniqueIndex loadFromPersistedPages(@Nonnull Serializable[][] valuePages, @Nonnull long[][] payloadPages) {
		final int[] pageSequences = new int[valuePages.length];
		for (int i = 0; i < valuePages.length; i++) {
			pageSequences[i] = i;
		}
		return GlobalUniqueIndex.fromPersistedPages(
			Scope.LIVE, URL_KEY, Integer.class, pageSequences, valuePages, payloadPages, valuePages.length - 1, Map.of()
		);
	}

	@Nested
	@DisplayName("Strict-prefix twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should reject a stale strict-prefix twin page with GenericEvitaInternalError on reload")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new Serializable[][]{
						valuePage(0, TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						payloadPage(0, TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					}
				),
				"A stale strict-prefix leaf-page twin must fail fast on reload."
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
		@DisplayName("should throw on a same-value twin pointing at a different packed payload than its superseder")
		void shouldRefuseDivergedPayloadTwin() {
			final long[] divergedPayloads = payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE);
			// same value as the twin's first value, but a different owning entity primary key => not a strict prefix
			divergedPayloads[0] = NumberUtils.pack(0, 0, 987_654);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new Serializable[][]{
						valuePage(0, TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						valuePage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						payloadPage(0, TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						divergedPayloads
					}
				),
				"A same-value twin pointing at a different packed payload is an unknown corruption shape and must fail fast."
			);
		}
	}
}
