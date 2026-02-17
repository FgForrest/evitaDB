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

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Restricts the result set to entities that have at least one price valid at the specified date and time. This
 * constraint is one of the three primary price constraints (along with {@link PriceInCurrency} and
 * {@link PriceInPriceLists}) that together determine which price is selected as the entity's "price for sale" during
 * query evaluation.
 *
 * **Validity semantics:**
 *
 * A price is considered valid at a given moment if:
 * - The price has no validity range specified (treated as universally valid), OR
 * - The specified moment falls within the price's validity range (inclusive of both bounds)
 *
 * Prices without validity constraints pass all validity checks and are always eligible for selection, making them
 * suitable for default or permanent pricing.
 *
 * **Constraint variants:**
 *
 * This constraint supports two forms via the suffix mechanism defined by {@link io.evitadb.api.query.ConstraintWithSuffix}:
 *
 * 1. **Explicit datetime variant** — checks validity at a specific moment:
 * ```
 * priceValidIn(2020-07-30T20:37:50+00:00)
 * ```
 *
 * 2. **Current time variant** — checks validity at query execution time (deferred evaluation):
 * ```
 * priceValidInNow()
 * ```
 *
 * The `priceValidInNow()` variant is evaluated lazily during query execution using the engine's current timestamp. This
 * supports caching scenarios where the query structure remains constant but the effective validity moment changes with
 * each execution.
 *
 * **Usage patterns:**
 *
 * Standalone usage (filters entities by price validity):
 * ```
 * priceValidIn(2025-12-25T00:00:00Z)
 * ```
 *
 * Standard e-commerce query (combines validity with currency and price lists):
 * ```
 * filterBy(
 *   and(
 *     priceInCurrency("USD"),
 *     priceInPriceLists("seasonal-winter", "basic"),
 *     priceValidInNow()
 *   )
 * )
 * ```
 *
 * **Typical scenarios:**
 *
 * - **Seasonal pricing:** Define time-limited prices for holidays, sales events, or promotional periods. Outside the
 *   validity window, the entity either uses a fallback price or is excluded if no valid price exists.
 * - **Future pricing:** Schedule price changes in advance by defining validity ranges that start in the future.
 * - **Price history:** Query historical pricing by specifying past timestamps.
 *
 * **Behavior and constraints:**
 *
 * - Entities must have at least one price valid at the specified moment to match. Entities without any valid prices
 *   are excluded from the result set.
 * - When combined with {@link PriceInCurrency}, only prices in the target currency are considered.
 * - When combined with {@link PriceInPriceLists}, the price list priority algorithm selects among the valid prices.
 * - Only one `priceValidIn` constraint is allowed per query. Multiple validity moments cannot be specified in a single
 *   filter (no OR logic for validity within a single query).
 * - This constraint cannot be nested inside {@link UserFilter} — it defines a mandatory pricing context that applies
 *   to the entire query and cannot be toggled by user interface controls.
 *
 * **Price for sale calculation:**
 *
 * The selected "price for sale" is the result of an algorithm that considers currency, price list priority, and
 * validity. See the [price for sale calculation documentation](https://evitadb.io/documentation/deep-dive/price-for-sale-calculation)
 * for the complete algorithm and edge case handling.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/filtering/price#price-valid-in)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "validIn",
	shortDescription = "The constraint checks if entity has selling price valid at the passed moment.",
	userDocsLink = "/documentation/filtering/price#price-valid-in",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceValidIn extends AbstractFilterConstraintLeaf
	implements PriceConstraint<FilterConstraint>, ConstraintWithSuffix, FilterConstraint {
	@Serial private static final long serialVersionUID = -3041416427283645494L;
	private static final String SUFFIX = "now";

	private PriceValidIn(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX)
	public PriceValidIn() {
		super();
	}

	@Creator
	public PriceValidIn(@Nonnull OffsetDateTime theMoment) {
		super(theMoment);
	}

	/**
	 * Returns {@link OffsetDateTime} that should be verified whether is within the range (inclusive) of price validity.
	 */
	@Nullable
	public OffsetDateTime getTheMoment(@Nonnull Supplier<OffsetDateTime> currentDateAndTime) {
		return getArguments().length == 0 ? currentDateAndTime.get() : (OffsetDateTime) getArguments()[0];
	}

	/**
	 * Returns {@link OffsetDateTime} that should be verified whether is within the range (inclusive) of price validity.
	 * Note for internal use only, uses current date and time.
	 */
	@Nullable
	private OffsetDateTime getTheMoment() {
		return getTheMoment(OffsetDateTime::now);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return getArguments().length == 0 ? of(SUFFIX) : empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceValidIn(newArguments);
	}
}
