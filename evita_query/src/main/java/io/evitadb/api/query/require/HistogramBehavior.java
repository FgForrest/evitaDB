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
 * The values were originally laid out as a two-dimensional matrix of boundary placement against empty-bucket
 * handling. Only the equal-width column ever had two distinct behaviours:
 *
 * |                    | Equal-width intervals | Frequency-equalised intervals                   |
 * |--------------------|-----------------------|-------------------------------------------------|
 * | Keep empty buckets | `STANDARD`            | -                                               |
 * | Drop empty buckets | `OPTIMIZED`           | `EQUALIZED` (`EQUALIZED_OPTIMIZED`, deprecated)  |
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
 * - Keep empty (`STANDARD`): the response always contains exactly the number of buckets requested, even if some
 *   buckets contain no entities. Useful when the UI must maintain a fixed-width display.
 * - Drop empty (`OPTIMIZED`): buckets with no entities are removed, producing a more compact histogram. The response
 *   may contain fewer buckets than requested. Recommended for most interactive UIs as it avoids confusing empty
 *   columns.
 *
 * This axis **collapsed for the frequency-equalised family**, which places every boundary on a value the data
 * actually contains and therefore never produces an empty bucket in the first place - leaving the "optimized"
 * variant with nothing to drop. `EQUALIZED` and `EQUALIZED_OPTIMIZED` consequently behave identically, and the
 * latter is deprecated; both return at most the requested number of buckets, and both return fewer whenever a value
 * held by many entities collapses several quantile intervals into one - there is simply no distinct value left to
 * split them at. Clients must not assume the requested count came back.
 *
 * **Reading `relativeFrequency`**
 *
 * For the equal-width family it is the share of entities in the bucket and the values sum to 100. For the
 * frequency-equalised family the occurrences are approximately constant by construction and carry no information, so
 * the field instead carries the smoothed **value density** at the bucket, normalised so that the tallest point of the
 * distribution reads 100. Scale bars against the constant 100, never against the sum or against the tallest returned
 * bucket - the full rendering contract is documented on `HistogramContract.Bucket#relativeFrequency()`.
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
	 * Histogram will never contain more buckets than you asked for, and contains fewer whenever a single value is
	 * held by so many entities that it collapses several quantile intervals into one.
	 * Bucket boundaries are positioned on the empirical quantile function, so each bucket covers approximately equal
	 * portion of total records. This provides better user experience when data is heavily skewed. Every boundary is a
	 * value the data actually contains, so no bucket is ever empty and every slider position selects a different set
	 * of entities.
	 */
	EQUALIZED,
	/**
	 * Identical to {@link #EQUALIZED}. The equalised algorithm never emits an empty bucket, so there is nothing for
	 * the "optimized" variant to drop; the constant is kept because it is part of the published query grammar.
	 *
	 * @deprecated use {@link #EQUALIZED} instead - the keep/drop-empty axis has no meaning for the frequency-equalised
	 * family, so this constant has never behaved differently and is retained only for backward compatibility of the
	 * published query grammar
	 */
	@Deprecated(since = "2026.2")
	EQUALIZED_OPTIMIZED

}
