/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * This enumeration describes the behaviour of {@link AttributeHistogram} and {@link PriceHistogram} calculation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SupportedEnum
public enum HistogramBehavior {

	/**
	 * Histogram always contains the number of buckets you asked for. This is the default behaviour.
	 * Bucket boundaries are positioned at equal intervals across the value range.
	 */
	STANDARD,
	/**
	 * Histogram will never contain more buckets than you asked for, but may contain less when the data is scarce and
	 * there would be big gaps (empty buckets) between buckets. This leads to more compact histograms, which provide
	 * better user experience.
	 * Bucket boundaries are positioned at equal intervals across the value range.
	 */
	OPTIMIZED,
	/**
	 * Histogram always contains the number of buckets you asked for.
	 * Bucket boundaries are positioned based on cumulative frequency distribution, so each bucket covers
	 * approximately equal portion of total records. This provides better user experience when data is heavily skewed.
	 */
	EQUALIZED,
	/**
	 * Histogram will never contain more buckets than you asked for, but may contain less when the data is scarce.
	 * Bucket boundaries are positioned based on cumulative frequency distribution, so each bucket covers
	 * approximately equal portion of total records. This provides better user experience when data is heavily skewed.
	 */
	EQUALIZED_OPTIMIZED

}
