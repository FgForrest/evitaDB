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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the stale leaf-page twin corruption on the {@link PriceListAndCurrencyPriceSuperIndex} paged restore
 * path: a writer race can leave a frozen stale snapshot of a leaf page persisted alongside the page that superseded
 * it. The price-record tree is an element-keyed `TransactionalElementBPlusTree` (keyed by `internalPriceId`)
 * persisted per leaf page; the spine builder validates strict cross-leaf key order, so a twin whose key run overlaps
 * its successor leaf page (the corruption shape that previously loaded silently and, amplified, fed the SAME price
 * record twice into {@code EntityPrices.addPriceRecord}) is now detected at reassembly.
 *
 * The reload must reject any such twin with {@link GenericEvitaInternalError} — it fails fast at
 * {@link PriceListAndCurrencyPriceSuperIndex#fromPersistedPages} rather than assembling a corrupt index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(PRICE)
@DisplayName("Stale leaf-page twin fails fast on the PriceListAndCurrencyPriceSuperIndex reload path")
class PriceListAndCurrencyPriceSuperIndexStaleLeafPageTwinTest {

	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	/**
	 * Twin/successor page sizes mirror the observed corruption shape (a strict-prefix twin next to a longer successor
	 * that grew before the frozen twin was superseded) but are scaled to fit within a single element-tree leaf (default
	 * block size 64) — a persisted leaf page is always one leaf, so both must stay under that capacity.
	 */
	private static final int TWIN_PREFIX_SIZE = 30;
	private static final int GROWN_PAGE_SIZE = 50;
	/** First internal price id of the twin/superseder run (1-based to avoid a 0 internal price id). */
	private static final int TWIN_BASE = 1 + TWIN_PREFIX_SIZE;

	/**
	 * Produces a distinct price record keyed on its internal price id, with `entityPrimaryKey == internalPriceId` so a
	 * duplicated record surfaces in that entity's {@code EntityPrices}. Content is derivable from the key, so a twin's
	 * prefix record is byte-identical to the superseding page's record.
	 *
	 * @param internalPriceId the internal price id (the tree key and the owning entity primary key)
	 * @return the price record
	 */
	@Nonnull
	private static PriceRecord rec(int internalPriceId) {
		return new PriceRecord(
			internalPriceId, internalPriceId + 1000, internalPriceId, internalPriceId * 100 + 21, internalPriceId * 100
		);
	}

	/**
	 * Builds a page of price records for the internal-price-id ordinals `[from, to)`.
	 *
	 * @param from the first ordinal (inclusive)
	 * @param to   the last ordinal (exclusive)
	 * @return the page records in ascending internal-price-id order
	 */
	@Nonnull
	private static PriceRecordContract[] page(int from, int to) {
		final PriceRecordContract[] records = new PriceRecordContract[to - from];
		for (int i = from; i < to; i++) {
			records[i - from] = rec(i);
		}
		return records;
	}

	/**
	 * Loads a {@link PriceListAndCurrencyPriceSuperIndex} from the passed persisted pages the way the catalog-open path
	 * does (see `PriceSuperIndexLoader`): page sequences `0..n-1`, an empty validity index.
	 *
	 * @param pages the persisted leaf pages in list order
	 * @return the rebuilt index
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex loadFromPersistedPages(@Nonnull PriceRecordContract[]... pages) {
		final int[] pageSequences = new int[pages.length];
		for (int i = 0; i < pages.length; i++) {
			pageSequences[i] = i;
		}
		return PriceListAndCurrencyPriceSuperIndex.fromPersistedPages(
			PRICE_INDEX_KEY, new RangeIndex(), pageSequences, pages, pages.length - 1
		);
	}

	@Nested
	@DisplayName("Strict-prefix twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should throw on a stale strict-prefix twin page instead of assembling a corrupt index")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					// healthy predecessor page (internal price ids 1..TWIN_PREFIX_SIZE)
					page(1, 1 + TWIN_PREFIX_SIZE),
					// the frozen STALE twin (page sequence 1): internal price ids TWIN_BASE .. TWIN_BASE+TWIN_PREFIX_SIZE
					page(TWIN_BASE, TWIN_BASE + TWIN_PREFIX_SIZE),
					// the superseder (page sequence 2): same prefix plus later ids
					page(TWIN_BASE, TWIN_BASE + GROWN_PAGE_SIZE)
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
		@DisplayName("should throw on a same-price-id twin whose price record diverges from its superseder")
		void shouldRefuseDivergedPriceRecordTwin() {
			final PriceRecordContract[] divergedSuperseder = page(TWIN_BASE, TWIN_BASE + GROWN_PAGE_SIZE);
			// same internal price id as the twin's first record, but a different price amount => not a strict prefix
			divergedSuperseder[0] = new PriceRecord(TWIN_BASE, TWIN_BASE + 1000, TWIN_BASE, 424_242, 424_200);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(1, 1 + TWIN_PREFIX_SIZE),
					page(TWIN_BASE, TWIN_BASE + TWIN_PREFIX_SIZE),
					divergedSuperseder
				),
				"A same-price-id twin with a diverged price record is an unknown corruption shape and must fail fast."
			);
		}
	}
}
