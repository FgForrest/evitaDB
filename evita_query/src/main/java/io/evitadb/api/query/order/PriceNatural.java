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

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.PriceType;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `priceNatural` constraint allows output entities to be sorted by their selling price in their natural numeric
 * order. It requires only the order direction and the price constraints in the `filterBy` section of the query.
 * The price variant (with or without tax) is determined by the {@link PriceType} requirement of the query (price with
 * tax is used by default).
 *
 * Please read the [price for sale calculation algorithm documentation](https://evitadb.io/documentation/deep-dive/price-for-sale-calculation)
 * to understand how the price for sale is calculated.
 *
 * Example:
 *
 * ```evitaql
 * priceNatural()
 * priceNatural(DESC)
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/price#price-natural)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "natural",
	shortDescription = "Sorts returned entities by their selling price in natural numeric order.",
	userDocsLink = "/documentation/query/ordering/price#price-natural",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class PriceNatural extends AbstractOrderConstraintLeaf implements PriceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -1480893202599544659L;

	private PriceNatural(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	public PriceNatural() {
		super(OrderDirection.ASC);
	}

	@Creator
	public PriceNatural(@Nonnull OrderDirection orderDirection) {
		super(orderDirection);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	/**
	 * Returns the order direction in which entities are sorted by their selling price.
	 */
	@Nonnull
	public OrderDirection getOrderDirection() {
		return (OrderDirection) getArguments()[0];
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceNatural(newArguments);
	}

}
