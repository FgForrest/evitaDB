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

import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bitmap and resolved price outputs of one computation. Both arrays are ordered before they can be published.
 * Arrays, records and component maps remain read-only after publication; the tuple keeps their original instances.
 *
 * @param result computed bitmap
 * @param priceRecords resolved selling prices
 * @param perInnerRecordPriceRecords optional winning prices for individual inner records
 * @param weight complete admitted charge in bytes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CachedPriceRecordsResult(
	@Nonnull Bitmap result,
	@Nonnull ResolvedFilteredPriceRecords priceRecords,
	@Nullable ResolvedFilteredPriceRecords perInnerRecordPriceRecords,
	int weight
) implements CachedResult {
	/** Freezes both arrays in entity-primary-key order on the publishing thread. */
	public CachedPriceRecordsResult {
		priceRecords.getPriceRecords();
		if (perInnerRecordPriceRecords != null) {
			perInnerRecordPriceRecords.getPriceRecords();
		}
	}

	/**
	 * Captures the bitmap and the price outputs of one computation of a price formula. Each of those formulas assigns
	 * its outputs only as {@link ResolvedFilteredPriceRecords} - when computing and when restoring a tuple - so the
	 * casts hold on every path, including a sampled recomputation, which captures its tuple without
	 * {@link CachedResultWeigher} refusing other holders first.
	 *
	 * @param result computed bitmap
	 * @param priceRecords resolved selling prices of the same computation
	 * @param perInnerRecordPriceRecords resolved per-inner-record prices of the same computation, or null
	 * @param weight complete admitted charge in bytes
	 * @return the tuple with both arrays frozen
	 */
	@Nonnull
	public static CachedPriceRecordsResult of(
		@Nonnull Bitmap result, @Nonnull FilteredPriceRecords priceRecords,
		@Nullable FilteredPriceRecords perInnerRecordPriceRecords, int weight
	) {
		return new CachedPriceRecordsResult(result, (ResolvedFilteredPriceRecords) priceRecords,
			(ResolvedFilteredPriceRecords) perInnerRecordPriceRecords, weight);
	}
}
