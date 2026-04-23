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

import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.dataType.SupportedEnum;

/**
 * Controls how bucket boundaries are computed and whether empty buckets are eliminated when computing
 * {@link AttributeHistogram} and {@link PriceHistogram} results.
 *
 * The four values form a two-dimensional matrix:
 *
 * |                   | Equal-width intervals | Frequency-equalised intervals |
 * |-------------------|-----------------------|-------------------------------|
 * | Keep empty buckets| `STANDARD`            | `EQUALIZED`                   |
 * | Drop empty buckets| `OPTIMIZED`           | `EQUALIZED_OPTIMIZED`         |
 *
 * **Equal-width vs. frequency-equalised boundaries**
 *
 * - Equal-width (`STANDARD`, `OPTIMIZED`): the value range [min, max] is divided into *N* equally sized intervals.
 *   This is the classic histogram approach and is predictable, but buckets may be very uneven in population when data
 *   is heavily skewed (e.g. most products priced between 0–50 EUR and a few luxury items above 500 EUR).
 * - Frequency-equalised (`EQUALIZED`, `EQUALIZED_OPTIMIZED`): bucket boundaries are placed so that each bucket
 *   covers approximately the same *number of entities*. This produces a more balanced visual distribution and is
 *   preferable for heavily skewed data, but the bucket widths vary.
 *
 * **Keep vs. drop empty buckets**
 *
 * - Keep empty (`STANDARD`, `EQUALIZED`): the response always contains exactly the number of buckets requested,
 *   even if some buckets contain no entities. Useful when the UI must maintain a fixed-width display.
 * - Drop empty (`OPTIMIZED`, `EQUALIZED_OPTIMIZED`): buckets with no entities are removed, producing a more compact
 *   histogram. The response may contain fewer buckets than requested. Recommended for most interactive UIs as it
 *   avoids confusing empty columns.
 *
 * `STANDARD` is the default value and is treated as an implicit argument in the EvitaQL string representation
 * (see {@link ConstraintWithDefaults}).
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
