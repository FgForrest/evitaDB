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
 * The `priceInCurrency` constraint can be used to limit the result set to entities that have a price in the specified
 * currency. Except for the <a href="https://evitadb.io/documentation/query/filtering/price?lang=evitaql#typical-usage-of-price-constraints">standard use-case</a>
 * you can also create query with this constraint only:
 *
 * <pre>
 * priceInCurrency("EUR")
 * </pre>
 *
 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-in-currency">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inCurrency",
	shortDescription = "The constraint filters out all entities that lack selling price in specified currency.",
	userDocsLink = "/documentation/query/filtering/price#price-in-currency",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceInCurrency extends AbstractFilterConstraintLeaf implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = -6188252788595824381L;

	private PriceInCurrency(@Nonnull Serializable... arguments) {
		super(null, arguments);
	}

	public PriceInCurrency(@Nonnull String currency) {
		super(null, currency);
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
