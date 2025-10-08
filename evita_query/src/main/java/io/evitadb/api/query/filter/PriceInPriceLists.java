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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `priceInPriceLists` constraint defines the allowed set(s) of price lists that the entity must have to be included
 * in the result set. The order of the price lists in the argument is important for the final price for sale calculation
 * - see the <a href="https://evitadb.io/documentation/deep-dive/price-for-sale-calculation">price for sale calculation
 * algorithm documentation</a>. Price list names are represented by plain String and are case-sensitive. Price lists
 * don't have to be stored in the database as an entity, and if they are, they are not currently associated with
 * the price list code defined in the prices of other entities. The pricing structure is simple and flat for now
 * (but this may change in the future).
 *
 * Except for the <a href="https://evitadb.io/documentation/query/filtering/price?lang=evitaql#typical-usage-of-price-constraints">standard use-case</a>
 * you can also create query with this constraint only:
 *
 * <pre>
 * priceInPriceLists(
 *     "vip-group-1-level",
 *     "vip-group-2-level",
 *     "vip-group-3-level"
 * )
 * </pre>
 *
 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-in-price-lists">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inPriceLists",
	shortDescription = "The constraint filters out all entities that lack selling price in specified price lists. " +
		"Order of price lists also defines priority for selecting the entity selling price - the price from first price " +
		"list in the list will be used as a selling price for the entire entity.",
	userDocsLink = "/documentation/query/filtering/price#price-in-price-lists",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceInPriceLists extends AbstractFilterConstraintLeaf implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = 7018968762648494243L;

	private PriceInPriceLists(@Nonnull Serializable... priceLists) {
		super(priceLists);
	}

	@Creator
	public PriceInPriceLists(@Nonnull String... priceLists) {
		super(priceLists);
	}

	/**
	 * Returns primary keys of all price lists that should be considered in query.
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments()).map(String.class::cast).toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceInPriceLists(newArguments);
	}
}
