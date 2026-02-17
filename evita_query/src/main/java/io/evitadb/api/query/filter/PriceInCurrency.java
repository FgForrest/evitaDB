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
import java.util.Currency;

/**
 * Restricts the result set to entities that have at least one price defined in the specified currency. This constraint
 * is one of the three primary price constraints (along with {@link PriceInPriceLists} and {@link PriceValidIn}) that
 * together determine which price is selected as the entity's "price for sale" during query evaluation.
 *
 * **Currency identification:**
 * Accepts ISO 4217 three-letter currency codes (case-sensitive), such as `EUR`, `USD`, or `CZK`. The constraint
 * internally normalizes String arguments to `java.util.Currency` objects to ensure consistent equality comparison.
 *
 * **Usage patterns:**
 *
 * Standalone usage (filters entities by currency availability):
 * ```
 * priceInCurrency("EUR")
 * ```
 *
 * Standard e-commerce query (combines currency with price lists and validity):
 * ```
 * filterBy(
 *   and(
 *     priceInCurrency("CZK"),
 *     priceInPriceLists("basic", "vip"),
 *     priceValidInNow()
 *   )
 * )
 * ```
 *
 * **Behavior and constraints:**
 *
 * - Entities must have at least one price in the specified currency to match. Entities without any prices in the
 *   target currency are excluded from the result set.
 * - When combined with {@link PriceInPriceLists}, the engine applies price list priority to select the correct selling
 *   price among multiple prices in the same currency.
 * - When combined with {@link PriceValidIn}, only prices valid at the specified moment are considered.
 * - Only one `priceInCurrency` constraint is allowed per query. Multiple currencies cannot be specified in a single
 *   filter (no OR logic for currencies within a single query).
 * - This constraint cannot be nested inside {@link UserFilter} — it defines a mandatory pricing context that applies
 *   to the entire query and cannot be toggled by user interface controls.
 *
 * **Price for sale calculation:**
 *
 * evitaDB uses an ordered priority algorithm to select a single "price for sale" per entity. The selected price is
 * influenced by the combination of `priceInCurrency`, `priceInPriceLists`, and `priceValidIn` constraints. See the
 * [price for sale calculation documentation](https://evitadb.io/documentation/deep-dive/price-for-sale-calculation)
 * for the complete algorithm.
 *
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-in-currency)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inCurrency",
	shortDescription = "The constraint filters out all entities that lack selling price in specified currency.",
	userDocsLink = "/documentation/query/filtering/price#price-in-currency",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceInCurrency extends AbstractFilterConstraintLeaf
	implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = -6188252788595824381L;

	private PriceInCurrency(@Nonnull Serializable... arguments) {
		super(normalizeToCurrency(arguments));
	}

	public PriceInCurrency(@Nonnull String currency) {
		super(Currency.getInstance(currency));
	}

	/**
	 * Normalizes any String arguments to Currency objects so that equality comparison works
	 * regardless of whether the constraint was constructed with a String or Currency argument.
	 */
	@Nonnull
	private static Serializable[] normalizeToCurrency(@Nonnull Serializable[] arguments) {
		final Serializable[] normalized = new Serializable[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			final Serializable arg = arguments[i];
			normalized[i] = arg instanceof String ? Currency.getInstance((String) arg) : arg;
		}
		return normalized;
	}

	@Creator
	public PriceInCurrency(@Nonnull Currency currency) {
		super(currency);
	}

	/**
	 * Returns currency ISO code that should be considered for price evaluation.
	 */
	@Nonnull
	public Currency getCurrency() {
		final Serializable argument = getArguments()[0];
		return argument instanceof Currency ? (Currency) argument : Currency.getInstance(argument.toString());
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceInCurrency(newArguments);
	}
}
