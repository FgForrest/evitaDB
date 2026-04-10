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

/**
 * Restricts the result set to entities that have at least one price in any of the specified price lists. This
 * constraint is one of the three primary price constraints (along with {@link PriceInCurrency} and
 * {@link PriceValidIn}) that together determine which price is selected as the entity's "price for sale" during query
 * evaluation.
 *
 * **Price list identification and priority:**
 *
 * Price list names are case-sensitive String identifiers. The order in which price lists are specified is critical —
 * it defines the priority for selecting the final "price for sale". When an entity has multiple prices that satisfy
 * the currency and validity constraints, the engine selects the price from the first matching price list in the
 * provided order.
 *
 * Price lists are lightweight identifiers and do not need to exist as separate entities in the database. They are
 * simply String codes attached to entity prices. The pricing structure is flat — there is no inheritance or hierarchy
 * among price lists (this may change in future versions).
 *
 * **Usage patterns:**
 *
 * Standalone usage (filters entities by price list availability with priority):
 * ```
 * priceInPriceLists("vip-level-1", "vip-level-2", "basic")
 * ```
 *
 * Standard e-commerce query (combines price lists with currency and validity):
 * ```
 * filterBy(
 *   and(
 *     priceInCurrency("EUR"),
 *     priceInPriceLists("vip", "reference", "basic"),
 *     priceValidInNow()
 *   )
 * )
 * ```
 *
 * In the above example, an entity with prices in both `vip` and `basic` price lists will use the `vip` price as its
 * "price for sale" because `vip` appears first in the priority list.
 *
 * **Behavior and constraints:**
 *
 * - Entities must have at least one price in one of the specified price lists to match. Entities without any matching
 *   prices are excluded from the result set.
 * - Priority is left-to-right: the first price list match determines the selected price.
 * - When combined with {@link PriceInCurrency}, only prices in the target currency are considered.
 * - When combined with {@link PriceValidIn}, only prices valid at the specified moment are considered.
 * - Only one `priceInPriceLists` constraint is allowed per query. Multiple independent price list sets cannot be
 *   specified in a single filter (no OR logic for price lists within a single query).
 * - This constraint cannot be nested inside {@link UserFilter} — it defines a mandatory pricing context that applies
 *   to the entire query and cannot be toggled by user interface controls.
 *
 * **Price for sale calculation:**
 *
 * The selected "price for sale" is the result of a priority-based algorithm that considers currency, validity, and
 * price list order. See the [price for sale calculation documentation](https://evitadb.io/documentation/deep-dive/price-for-sale-calculation)
 * for the complete algorithm and edge case handling.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-in-price-lists)
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
public class PriceInPriceLists extends AbstractFilterConstraintLeaf
	implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = 7018968762648494243L;

	private PriceInPriceLists(@Nonnull Serializable... priceLists) {
		super(priceLists);
	}

	@Creator
	public PriceInPriceLists(@Nonnull String... priceLists) {
		super(priceLists);
	}

	/**
	 * Returns names of all price lists that should be considered in query.
	 */
	@Nonnull
	public String[] getPriceLists() {
		final Serializable[] arguments = getArguments();
		final String[] result = new String[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			result[i] = (String) arguments[i];
		}
		return result;
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
