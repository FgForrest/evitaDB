/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.order.PriceNatural;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `useOfPrice` require constraint can be used to control the form of prices that will be used for computation in
 * {@link io.evitadb.api.query.filter.PriceBetween} filtering, and {@link PriceNatural},
 * ordering. Also {@link PriceHistogram} is sensitive to this setting.
 *
 * By default, end customer form of price (e.g. price with tax) is used in all above-mentioned constraints. This could
 * be changed by using this requirement query. It has single argument that can have one of the following values:
 *
 * - WITH_TAX
 * - WITHOUT_TAX
 *
 * Example:
 *
 * ```
 * useOfPrice(WITH_TAX)
 * ```
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/price#price-type">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "type",
	shortDescription = "The constraint specifies which price type (with/without tax) will be used for handling filtering and sorting constraints.",
	userDocsLink = "/documentation/query/requirements/price#price-type"
)
public class PriceType extends AbstractRequireConstraintLeaf implements PriceConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -7156758352138266166L;

	@Creator
	public PriceType(@Nonnull QueryPriceMode queryPriceMode) {
		super(queryPriceMode);
	}

	/**
	 * Returns number of the items that should be omitted in the result.
	 */
	public QueryPriceMode getQueryPriceMode() {
		return (QueryPriceMode) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceType(getQueryPriceMode());
	}

}
