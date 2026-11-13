/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.cache;

import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.core.cache.CachedResultTestSupport.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached result tuples and publication ordering without an engine or a cache instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cached result payloads")
@Tag(ENGINE)
@Tag(CACHE)
class CachedResultTest {
	/** Checks that both side outputs are frozen while preserving the exact objects used for restoration. */
	@Test
	@DisplayName("Freezes both price arrays and retains all restore inputs")
	void shouldFreezeBothPriceArrays() {
		final BaseBitmap bitmap = new BaseBitmap(10, 20);
		final ResolvedFilteredPriceRecords prices = records(
			new PriceRecord(2, 2, 20, 240, 200), new PriceRecord(1, 1, 10, 120, 100));
		final ResolvedFilteredPriceRecords inner = records(
			new PriceRecord(4, 4, 20, 360, 300), new PriceRecord(3, 3, 10, 180, 150));
		final CachedPriceRecordsResult cached = new CachedPriceRecordsResult(bitmap, prices, inner, 777);
		assertSame(bitmap, cached.result());
		assertSame(prices, cached.priceRecords());
		assertSame(inner, cached.perInnerRecordPriceRecords());
		assertEquals(777, cached.weight());
		assertEquals(SortingForm.ENTITY_PK, prices.getSortingForm());
		assertEquals(SortingForm.ENTITY_PK, inner.getSortingForm());
		assertEquals(10, prices.getPriceRecords()[0].entityPrimaryKey());
		assertEquals(10, inner.getPriceRecords()[0].entityPrimaryKey());
	}

	/** Checks absence of the optional histogram array and identity of bitmap and histogram payloads. */
	@Test
	@DisplayName("Preserves bitmap, histogram and empty histogram instances")
	void shouldKeepStoredInstances() {
		final BaseBitmap bitmap = new BaseBitmap(10);
		final CachedBitmapResult cached = new CachedBitmapResult(bitmap, 123);
		assertSame(bitmap, cached.result());
		assertEquals(123, cached.weight());
		assertNull(new CachedPriceRecordsResult(bitmap, records(), null, 123).perInnerRecordPriceRecords());
		final CacheableHistogramContract histogram = histogram();
		final CachedHistogramResult histogramResult = new CachedHistogramResult(histogram, 456);
		assertSame(histogram, histogramResult.histogram());
		assertEquals(456, histogramResult.weight());
		assertSame(CacheableHistogramContract.EMPTY,
			new CachedHistogramResult(CacheableHistogramContract.EMPTY, 789).histogram());
	}
}
