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

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `price` is ordering that sorts returned entities by most priority price in defined order direction in the first
 * optional argument.
 * Most priority price relates to [price computation algorithm](price_computation.md) described in special article.
 *
 * Example:
 *
 * ```
 * price()
 * price(DESC)
 * ```
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "natural",
	shortDescription = "The constraint sorts returned entities by selected price for sale.",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class PriceNatural extends AbstractOrderConstraintLeaf implements PriceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -1480893202599544659L;

	private PriceNatural(Serializable... arguments) {
		super(arguments);
	}

	public PriceNatural() {
		super(OrderDirection.ASC);
	}

	@Creator
	public PriceNatural(@Nonnull @Value OrderDirection orderDirection) {
		super(orderDirection);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	/**
	 * Returns order direction in the most prioritized prices must be sorted.
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
