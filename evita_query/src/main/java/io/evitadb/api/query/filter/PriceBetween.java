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
 * The `priceBetween` constraint restricts the result set to items that have a price for sale within the specified price
 * range. This constraint is typically set by the user interface to allow the user to filter products by price, and
 * should be nested inside the userFilter constraint container so that it can be properly handled by the facet or
 * histogram computations.
 *
 * Example:
 *
 * <pre>
 * priceBetween(150.25, 220.0)
 * </pre>
 *
 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-between">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "between",
	shortDescription = "The constraint checks if entity has price for sale within the passed range of prices (both ends are inclusive).",
	userDocsLink = "/documentation/query/filtering/price#price-between",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceBetween extends AbstractFilterConstraintLeaf implements PriceConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = -4134467514999931163L;

	private PriceBetween(Serializable... arguments) {
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
