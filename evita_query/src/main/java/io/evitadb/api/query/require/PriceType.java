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
 * The `priceType` require constraint selects which price variant — with tax or without tax — is used as the operative
 * price for all price-sensitive computations in the query. Specifically it affects:
 *
 * - {@link io.evitadb.api.query.filter.PriceBetween} — the range filter is applied against the selected price variant.
 * - {@link PriceNatural} ordering — entities are sorted by the selected price variant.
 * - {@link PriceHistogram} — the histogram buckets are built from the selected price variant.
 *
 * When the constraint is absent, the default operative price is **with tax** ({@link QueryPriceMode#WITH_TAX}), which
 * is the appropriate default for B2C (consumer-facing) storefronts. B2B storefronts that display prices without VAT
 * should explicitly set `priceType(WITHOUT_TAX)`.
 *
 * The constraint accepts a single mandatory argument of type {@link QueryPriceMode}.
 *
 * **Example**
 *
 * ```evitaql
 * priceType(WITH_TAX)
 * priceType(WITHOUT_TAX)
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#price-type)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "type",
	shortDescription = "The constraint selects which price variant (with or without tax) is used for price filtering, sorting, and histogram computation.",
	userDocsLink = "/documentation/query/requirements/price#price-type"
)
public class PriceType extends AbstractRequireConstraintLeaf implements PriceConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -7156758352138266166L;

	@Creator
	public PriceType(@Nonnull QueryPriceMode queryPriceMode) {
		super(queryPriceMode);
	}

	/**
	 * Returns the price mode that determines which form of price (with or without tax) should be used.
	 */
	@Nonnull
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
		return new PriceType((QueryPriceMode) newArguments[0]);
	}

}
