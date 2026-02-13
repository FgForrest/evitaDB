/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * Common interface for histogram data crunchers that compute histogram buckets from input data.
 * Implemented by both {@link HistogramDataCruncher} (equal-width buckets) and
 * {@link EqualizedHistogramDataCruncher} (equal-frequency buckets).
 *
 * @param <T> the type of source data elements
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface HistogramDataCruncherContract<T> {

	/**
	 * Returns the computed histogram as an array of buckets.
	 * Each bucket contains a threshold value and the count of occurrences.
	 *
	 * @return array of histogram buckets
	 */
	@Nonnull
	CacheableBucket[] getHistogram();

	/**
	 * Returns the maximum value (upper threshold) of the histogram range.
	 *
	 * Implementations may extend the histogram range beyond the maximum value
	 * present in the input data, so this value is guaranteed to be greater than
	 * or equal to the maximum input value, but not necessarily equal to it.
	 *
	 * @return maximum histogram threshold as {@link BigDecimal}
	 */
	@Nonnull
	BigDecimal getMaxValue();

}
