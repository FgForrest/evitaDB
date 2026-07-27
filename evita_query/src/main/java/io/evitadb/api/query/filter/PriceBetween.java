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
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Restricts the result set to entities whose "price for sale" falls within the specified numeric range. This constraint
 * filters entities based on their final computed selling price (as determined by {@link PriceInCurrency},
 * {@link PriceInPriceLists}, and {@link PriceValidIn}), not on individual price records.
 *
 * **Range semantics:**
 *
 * Both bounds are inclusive. An entity's price for sale must satisfy `from <= priceForSale <= to` to match. Either
 * bound can be `null` to represent an unbounded range:
 * - `priceBetween(100, null)` — matches prices >= 100
 * - `priceBetween(null, 500)` — matches prices <= 500
 * - `priceBetween(100, 500)` — matches prices between 100 and 500 (inclusive)
 *
 * **Usage patterns:**
 *
 * Standalone range filter (unbounded lower limit):
 * ```
 * priceBetween(null, 1000.00)
 * ```
 *
 * Typical user-driven filter nested in `userFilter` for correct facet and histogram computation:
 * ```
 * filterBy(
 *   and(
 *     priceInCurrency("USD"),
 *     priceInPriceLists("basic"),
 *     priceValidInNow(),
 *     userFilter(
 *       priceBetween(50.00, 200.00)
 *     )
 *   )
 * )
 * ```
 *
 * **UserFilter nesting for facet/histogram correctness:**
 *
 * In typical e-commerce scenarios, `priceBetween` should be nested inside the {@link UserFilter} container. This
 * distinction is critical for computing accurate facet counts and price histograms:
 *
 * - **Facet computation:** When calculating facet impact (e.g., "how many products would remain if I apply this brand
 *   filter?"), the engine temporarily removes constraints inside `userFilter` to compute baseline counts. If
 *   `priceBetween` is placed outside `userFilter`, it becomes a mandatory constraint that cannot be toggled, skewing
 *   facet statistics.
 *
 * - **Histogram computation:** Price histograms (bucket distributions of prices) are computed over the full result set
 *   excluding the `userFilter` scope. Placing `priceBetween` inside `userFilter` allows the histogram to show the
 *   distribution of all products, not just those already filtered by the user's price selection.
 *
 * If `priceBetween` is intentionally placed outside `userFilter`, it acts as a hard constraint that cannot be relaxed
 * during facet or histogram calculations. This is appropriate for programmatic filtering but not for user-controlled
 * UI widgets like price sliders.
 *
 * **Interaction with price constraints:**
 *
 * `priceBetween` operates on the final "price for sale" computed by the three primary price constraints. The entity
 * must first satisfy the currency, price list, and validity constraints to have a computable price for sale, and then
 * that price is checked against the `priceBetween` range.
 *
 * Example query with combined price constraints:
 * ```
 * filterBy(
 *   and(
 *     priceInCurrency("CZK"),
 *     priceInPriceLists("vip", "basic"),
 *     priceValidInNow(),
 *     userFilter(
 *       priceBetween(500, 2000)
 *     )
 *   )
 * )
 * ```
 *
 * In this query, only entities with a valid CZK price in the `vip` or `basic` price lists (prioritizing `vip`) that
 * falls between 500 and 2000 will be returned.
 *
 * **Behavior and constraints:**
 *
 * - Only entities with a computable "price for sale" are eligible for matching. Entities without any prices or without
 *   prices matching the currency/price list/validity constraints are excluded before `priceBetween` is evaluated.
 * - Both bounds are inclusive. Use `null` to express unbounded ranges.
 * - Only one `priceBetween` constraint is allowed per query. Multiple price ranges cannot be specified in a single
 *   filter (no OR logic for price ranges within a single query).
 * - The constraint is applicable only when at least one bound is non-null.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-between)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "between",
	shortDescription = "The constraint checks if entity has price for sale within the passed range of prices (both ends are inclusive).",
	userDocsLink = "/documentation/query/filtering/price#price-between",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceBetween extends AbstractFilterConstraintLeaf
	implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = -4134467514999931163L;

	private PriceBetween(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public PriceBetween(@Nullable BigDecimal from,
	                    @Nullable BigDecimal to) {
		super(from, to);
	}

	/**
	 * Returns lower bound of price (inclusive).
	 */
	@Nullable
	public BigDecimal getFrom() {
		return (BigDecimal) getArguments()[0];
	}

	/**
	 * Returns upper bound of price (inclusive).
	 */
	@Nullable
	public BigDecimal getTo() {
		return (BigDecimal) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length == 2 && (getFrom() != null || getTo() != null);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceBetween(newArguments);
	}
}
