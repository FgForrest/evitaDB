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
 * The `priceHistogram` is computed from the price for sale. The interval related constraints - i.e. {@link AttributeBetween}
 * and {@link PriceBetween} in the userFilter part are excluded for the sake of histogram calculation. If this weren't
 * the case, the user narrowing the filtered range based on the histogram results would be driven into a narrower and
 * narrower range and eventually into a dead end.
 *
 * It accepts two arguments:
 *
 * 1. The number of buckets (columns) the histogram should contain.
 * 2. The behavior of the histogram calculation - either STANDARD (default), where the exactly requested bucket count
 *    is returned or OPTIMIZED, where the number of columns is reduced if the data is scarce and there would be big gaps
 *    (empty buckets) between buckets. This leads to more compact histograms, which provide better user experience.
 *
 * The priceType requirement the source price property for the histogram computation. If no requirement, the histogram
 * visualizes the price with tax.
 *
 * Example:
 *
 * <pre>
 * priceHistogram(20)
 * priceHistogram(20, OPTIMIZED)
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/histogram#price-histogram">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "histogram",
	shortDescription = "The constraint triggers computation of the [histogram](https://en.wikipedia.org/wiki/Histogram) of price for sale into response.",
	userDocsLink = "/documentation/query/requirements/histogram#price-histogram"
)
public class PriceHistogram extends AbstractRequireConstraintLeaf implements ConstraintWithDefaults<RequireConstraint>, PriceConstraint<RequireConstraint>, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 7734875430759525982L;

	private PriceHistogram(Serializable... arguments) {
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
