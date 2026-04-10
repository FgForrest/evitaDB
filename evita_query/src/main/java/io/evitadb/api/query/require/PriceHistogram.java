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

package io.evitadb.api.query.require;

import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.PriceBetween;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `priceHistogram` require constraint triggers computation of a value-distribution histogram based on the
 * *price for sale* of the queried entities. The resulting histogram is included in the extra-results section of the
 * response and is intended to power a price-range slider or similar UI component.
 *
 * **How it works**
 *
 * The histogram is built from the prices of entities that pass the *mandatory* part of the `filterBy` constraint.
 * {@link AttributeBetween} and {@link PriceBetween} constraints placed inside `userFilter` are intentionally excluded
 * from the histogram calculation — the same rationale as for {@link AttributeHistogram}: without this exclusion a
 * user narrowing the price range would progressively shrink the available range until reaching a dead end.
 *
 * The price variant used for the histogram (with tax vs. without tax) is determined by the {@link PriceType} require
 * constraint present in the same query. When no `priceType` constraint is specified, the histogram defaults to the
 * *price with tax* (consumer-facing price).
 *
 * **Arguments**
 *
 * 1. `requestedBucketCount` (int, required) — the number of histogram buckets (columns) to produce.
 * 2. `behavior` ({@link HistogramBehavior}, optional, default `STANDARD`) — controls how bucket boundaries are
 *    positioned and whether empty buckets are suppressed:
 *    - `STANDARD`: exactly the requested number of equal-width buckets.
 *    - `OPTIMIZED`: up to the requested count, empty buckets are dropped for a denser result.
 *    - `EQUALIZED`: frequency-equalised boundaries so each bucket covers roughly the same number of entities.
 *    - `EQUALIZED_OPTIMIZED`: frequency-equalised boundaries with empty-bucket suppression combined.
 *
 * **ConstraintWithDefaults behaviour**
 *
 * `STANDARD` is an implicit (default) argument and is omitted from the EvitaQL string representation
 * (`priceHistogram(20)` rather than `priceHistogram(20,STANDARD)`).
 *
 * **Example**
 *
 * ```evitaql
 * priceHistogram(20)
 * priceHistogram(20, OPTIMIZED)
 * priceHistogram(50, EQUALIZED)
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#price-histogram)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "histogram",
	shortDescription = "The constraint triggers computation of a price-for-sale histogram into response extra results.",
	userDocsLink = "/documentation/query/requirements/histogram#price-histogram"
)
public class PriceHistogram extends AbstractRequireConstraintLeaf
	implements ConstraintWithDefaults<RequireConstraint>, PriceConstraint<RequireConstraint>, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 7734875430759525982L;

	private PriceHistogram(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public PriceHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior) {
		super(requestedBucketCount, behavior == null ? HistogramBehavior.STANDARD : behavior);
	}

	public PriceHistogram(int requestedBucketCount) {
		super(requestedBucketCount, HistogramBehavior.STANDARD);
	}

	/**
	 * Returns the number of optimal histogram buckets (columns) count that can be safely visualized to the user. Usually
	 * there is fixed size area dedicated to the histogram visualisation and there is no sense to return histogram with
	 * so many buckets (columns) that wouldn't be possible to render. For example - if there is 200px size for the histogram
	 * and we want to dedicate 10px for one column, it's wise to ask for 20 buckets.
	 */
	public int getRequestedBucketCount() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns the requested behavior of the histogram calculation.
	 *
	 * @return {@link HistogramBehavior#STANDARD} if not specified otherwise.
	 * @see HistogramBehavior
	 */
	@Nonnull
	public HistogramBehavior getBehavior() {
		return (HistogramBehavior) getArguments()[1];
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != HistogramBehavior.STANDARD)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == HistogramBehavior.STANDARD;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceHistogram(newArguments);
	}
}
