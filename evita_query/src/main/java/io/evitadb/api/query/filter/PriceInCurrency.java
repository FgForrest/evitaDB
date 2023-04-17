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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Currency;

/**
 * This `priceInCurrency` is query accepts single {@link String}
 * argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in ISO 4217 code or direct {@link Currency}
 * instance.
 *
 * Function returns true if entity has at least one price with specified currency. This function is also affected by
 * {@link PriceInPriceLists} function that limits the examined prices as well. When this query
 * is used in the query returned entities will contain only prices matching specified locale. In other words if entity has
 * two prices: USD and CZK and `priceInCurrency('CZK')` is used in query returned entity would have only Czech crown prices
 * fetched along with it.
 *
 * Only single `priceInCurrency` query can be used in the query. Constraint must be defined when other price related
 * constraints are used in the query.
 *
 * Example:
 *
 * ```
 * priceInCurrency('USD')
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inCurrency",
	shortDescription = "The constraint filters out all entities that lack selling price in specified currency.",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceInCurrency extends AbstractFilterConstraintLeaf implements PriceConstraint<FilterConstraint>, IndexUsingConstraint {
	@Serial private static final long serialVersionUID = -6188252788595824381L;

	private PriceInCurrency(@Nonnull Serializable... arguments) {
		super(null, arguments);
	}

	public PriceInCurrency(@Nonnull String currency) {
		super(null, currency);
	}

	@Creator
	public PriceInCurrency(@Nonnull @Value Currency currency) {
		super(currency);
	}

	/**
	 * Returns currency ISO code that should be considered for price evaluation.
	 */
	@Nonnull
	public Currency getCurrency() {
		final Serializable argument = getArguments()[0];
		return argument instanceof Currency ? (Currency) argument :  Currency.getInstance(argument.toString());
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
