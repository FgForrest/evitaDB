/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * The `attributeHistogram` can be computed from any filterable attribute whose type is numeric. The histogram is
 * computed only from the attributes of elements that match the current mandatory part of the filter. The interval
 * related constraints - i.e. {@link AttributeBetween} and {@link PriceBetween} in the userFilter part are excluded for
 * the sake of histogram calculation. If this weren't the case, the user narrowing the filtered range based on
 * the histogram results would be driven into a narrower and narrower range and eventually into a dead end.
 *
 * Example:
 *
 * <pre>
 * attributeHistogram(5, "width", "height")
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "histogram",
	shortDescription =  "The constraint triggers computation of the [histogram](https://en.wikipedia.org/wiki/Histogram) of specified attributes into response.",
	supportedValues = @ConstraintSupportedValues(supportedTypes = {Byte.class, Short.class, Integer.class, Long.class, BigDecimal.class})
)
public class AttributeHistogram extends AbstractRequireConstraintLeaf implements AttributeConstraint<RequireConstraint>, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = -3462067705883466799L;

	private AttributeHistogram(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeHistogram(int requestedBucketCount,
	                          @Nonnull String... attributeNames) {
		super(ArrayUtils.mergeArrays(new Serializable[]{requestedBucketCount}, attributeNames));
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
	 * Returns names of attributes for which histogram should be computed.
	 */
	@Nonnull
	public String[] getAttributeNames() {
		return Arrays.stream(getArguments())
			.skip(1)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeHistogram(newArguments);
	}
}
