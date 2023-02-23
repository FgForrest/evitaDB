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

import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `priceHistogram` requirement usage triggers computing and adding an object to the result index. It has single
 * argument that states the number of histogram buckets (columns) that can be safely visualized to the user. Usually
 * there is fixed size area dedicated to the histogram visualisation and there is no sense to return histogram with
 * so many buckets (columns) that wouldn't be possible to render. For example - if there is 200px size for the histogram
 * and we want to dedicate 10px for one column, it's wise to ask for 20 buckets.
 *
 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.Histogram} is stored to the result.
 * Histogram contains statistics on price layout in the query result.
 *
 * Example:
 *
 * ```
 * priceHistogram(20)
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "histogram",
	shortDescription = "The constraint triggers computation of the [histogram](https://en.wikipedia.org/wiki/Histogram) of price for sale into response."
)
public class PriceHistogram extends AbstractRequireConstraintLeaf implements PriceConstraint<RequireConstraint>, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 7734875430759525982L;

	private PriceHistogram(Serializable... arguments) {
		super(arguments);
	}

	@ConstraintCreatorDef
	public PriceHistogram(@ConstraintValueParamDef int requestedBucketCount) {
		super(requestedBucketCount);
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

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceHistogram(newArguments);
	}
}
