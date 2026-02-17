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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * The `attributeHistogram` require constraint triggers computation of a value-distribution histogram for one or more
 * numeric filterable attributes. The resulting histogram is included in the extra-results section of the response and
 * can be used to render a range-filter UI widget (e.g. a price slider) that guides users toward meaningful value ranges.
 *
 * **How it works**
 *
 * The histogram is calculated only over the entities that pass the *mandatory* part of the `filterBy` constraint
 * (i.e. the filter outside any `userFilter` container). Crucially, {@link AttributeBetween} and {@link PriceBetween}
 * constraints placed inside `userFilter` are intentionally excluded from the histogram calculation. Without this
 * exclusion, a user narrowing the filter range via the histogram would progressively shrink the available range until
 * reaching a dead end where no further selection is possible.
 *
 * **Arguments**
 *
 * 1. `requestedBucketCount` (int, required) — the number of histogram buckets (columns) to produce. Typical values
 *    are 10–50, chosen to match the pixel width of the histogram widget in the UI.
 * 2. `behavior` ({@link HistogramBehavior}, optional, default `STANDARD`) — controls how bucket boundaries are
 *    positioned and whether empty buckets are suppressed:
 *    - `STANDARD`: exactly the requested number of equal-width buckets, even if some are empty.
 *    - `OPTIMIZED`: up to the requested count, but empty buckets are dropped for a denser result.
 *    - `EQUALIZED`: exactly the requested count with frequency-equalised boundaries (each bucket covers roughly
 *      the same number of entities), ideal for skewed data distributions.
 *    - `EQUALIZED_OPTIMIZED`: frequency-equalised boundaries with empty-bucket suppression combined.
 * 3. `attributeNames` (String..., required, at least one) — names of the numeric filterable attributes for which
 *    histograms should be computed. Each named attribute produces a separate histogram in the response.
 *
 * The constraint is applicable only when at least one attribute name is provided; an instance with no attribute names
 * is not applicable and is ignored during query evaluation.
 *
 * **ConstraintWithDefaults behaviour**
 *
 * `STANDARD` is an implicit (default) argument and is omitted from the EvitaQL string representation
 * (`attributeHistogram(20,'width')` rather than `attributeHistogram(20,STANDARD,'width')`).
 *
 * **Example**
 *
 * ```evitaql
 * attributeHistogram(5, "width", "height")
 * attributeHistogram(5, OPTIMIZED, "width", "height")
 * attributeHistogram(20, EQUALIZED, "price")
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "histogram",
	shortDescription = "The constraint triggers computation of the histogram of specified numeric attributes into response extra results.",
	userDocsLink = "/documentation/query/requirements/histogram#attribute-histogram",
	supportedValues = @ConstraintSupportedValues(supportedTypes = {Byte.class, Short.class, Integer.class, Long.class, BigDecimal.class})
)
public class AttributeHistogram extends AbstractRequireConstraintLeaf
	implements ConstraintWithDefaults<RequireConstraint>, AttributeConstraint<RequireConstraint>, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = -3462067705883466799L;

	private AttributeHistogram(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeHistogram(
		int requestedBucketCount,
		@Nullable HistogramBehavior behavior,
		@Nonnull String... attributeNames
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{
					requestedBucketCount,
					behavior == null ? HistogramBehavior.STANDARD : behavior
				},
				attributeNames
			)
		);
	}

	public AttributeHistogram(int requestedBucketCount, @Nonnull String... attributeNames) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{
					requestedBucketCount,
					HistogramBehavior.STANDARD
				},
				attributeNames
			)
		);
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

	/**
	 * Returns names of attributes for which histogram should be computed.
	 */
	@Nonnull
	public String[] getAttributeNames() {
		return Arrays.stream(getArguments())
			.skip(2)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 2;
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
		return new AttributeHistogram(newArguments);
	}
}
