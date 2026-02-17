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

import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `defaultAccompanyingPriceLists` require constraint establishes the default ordered list of price-list names that
 * should be used when calculating *accompanying prices* for entities returned by the query. An accompanying price is
 * an informational price shown alongside the selling price — typical examples are "recommended retail price",
 * "previous price", or "list price" — and it is never used for filtering or ordering.
 *
 * **Relationship with `accompanyingPriceContent`**
 *
 * This constraint does **not** trigger accompanying price computation on its own. It acts as a query-level default that
 * is referenced whenever an {@link AccompanyingPriceContent} requirement inside an `entityFetch` (or similar) is used
 * in its parameterless form (`accompanyingPriceContent()`). In that case the engine substitutes the price lists
 * declared here as if they had been passed directly to `accompanyingPriceContent`. This eliminates repetition when
 * the same price list sequence is needed across multiple nested entity fetches in a single query.
 *
 * If an `accompanyingPriceContent` constraint is given its own price list arguments, those arguments take precedence
 * and this default is ignored for that particular occurrence.
 *
 * **Argument**
 *
 * An ordered list of price-list names (strings). The order is significant: the engine selects the first price it finds
 * when iterating through the list, matching the semantics of the standard price-for-sale calculation.
 *
 * **Example**
 *
 * ```evitaql
 * defaultAccompanyingPriceLists("reference", "basic")
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "defaultAccompanyingPriceLists",
	shortDescription = "The constraint defines the default ordered price list names used to calculate accompanying prices" +
		" — non-selling prices shown for comparison (e.g. 'previous price', 'recommended retail price').",
	userDocsLink = "/documentation/query/requirements/price#accompanying-price"
)
public class DefaultAccompanyingPriceLists extends AbstractRequireConstraintLeaf
	implements PriceConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5786325458930138452L;

	private DefaultAccompanyingPriceLists(@Nonnull Serializable... priceLists) {
		super(priceLists);
	}

	@Creator
	public DefaultAccompanyingPriceLists(@Nonnull String... priceLists) {
		super(priceLists);
	}

	/**
	 * Returns names of all price lists that should be used for default accompanying price calculation.
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments()).map(String.class::cast).toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new DefaultAccompanyingPriceLists(newArguments);
	}

}