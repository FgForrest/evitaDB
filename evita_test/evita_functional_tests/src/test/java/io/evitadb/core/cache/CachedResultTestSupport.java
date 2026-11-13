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

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.price.model.priceRecord.*;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * Real detached payloads shared by the cache value tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CachedResultTestSupport {
	/** Prevents instantiation of the fixture helper. */
	private CachedResultTestSupport() {
	}

	/** Builds unsorted resolved records so tests exercise the publication freeze. */
	@Nonnull
	static ResolvedFilteredPriceRecords records(@Nonnull PriceRecordContract... records) {
		return new ResolvedFilteredPriceRecords(records, SortingForm.NOT_SORTED);
	}

	/** Builds a price tuple with independently supplied record contents. */
	@Nonnull
	static CachedPriceRecordsResult prices(@Nonnull PriceRecordContract... records) {
		return new CachedPriceRecordsResult(new BaseBitmap(10), records(records), null, 100);
	}

	/** Builds a SUM record retaining all component entries and any unused map capacity. */
	@Nonnull
	static CumulatedVirtualPriceRecord sum(int count, int capacity) {
		final IntObjectHashMap<PriceRecordContract> map = new IntObjectHashMap<>(capacity);
		for (int i = 0; i < count; i++) {
			map.put(i, new PriceRecordInnerRecordSpecific(i + 1, i + 1, 10, i, 120, 100));
		}
		return new CumulatedVirtualPriceRecord(10, count * 120, QueryPriceMode.WITH_TAX, map);
	}

	/** Builds a histogram with separately allocated bounds, frequencies and large decimal magnitudes. */
	@Nonnull
	static CacheableHistogram histogram() {
		return new CacheableHistogram(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("1000.00"), 2, new BigDecimal("33.333")),
				new CacheableBucket(new BigDecimal("2000.00"), 4, new BigDecimal("66.667"))
			}, new BigDecimal("3000.00"),
			new BigDecimal("1000.000000000000000000000000000000000001"), Long.valueOf(3000)
		);
	}
}
