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

import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for all leaf order constraints — terminal nodes in the order constraint tree that cannot
 * contain child constraints. Leaf order constraints represent atomic ordering operations like sorting by attribute
 * value, price, entity primary key, or random ordering.
 *
 * **Design Purpose**
 *
 * This class specializes {@link ConstraintLeaf} for the order constraint domain by restricting the constraint type
 * to {@link OrderConstraint}. It serves as the foundation for all terminal ordering conditions in evitaDB queries,
 * analogous to columns in SQL `ORDER BY` clauses.
 *
 * **Class Hierarchy**
 *
 * The order constraint leaf hierarchy is organized by property domains:
 *
 * - **Attribute ordering**: {@link AttributeNatural} — orders by attribute value in natural (ascending/descending)
 *   order; {@link AttributeSetExact} — orders by explicit attribute value sequence;
 *   {@link AttributeSetInFilter} — orders by attribute values matching the filter
 * - **Price ordering**: {@link PriceNatural} — orders by selling price; {@link PriceDiscount} — orders by
 *   the discount amount between accompanying and selling price
 * - **Entity key ordering**: {@link EntityPrimaryKeyNatural} — orders by entity primary key;
 *   {@link EntityPrimaryKeyExact} — orders by explicit primary key sequence;
 *   {@link EntityPrimaryKeyInFilter} — orders by primary keys matching the filter
 * - **Random ordering**: {@link Random} — randomizes the order of results
 * - **Segment limit**: {@link SegmentLimit} — limits the number of entities in a segment
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 * @see AbstractOrderConstraintContainer
 * @see OrderConstraint
 * @see ConstraintLeaf
 */
abstract class AbstractOrderConstraintLeaf extends ConstraintLeaf<OrderConstraint> implements OrderConstraint {
	@Serial private static final long serialVersionUID = 3475944299512789481L;

	protected AbstractOrderConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
	}

	protected AbstractOrderConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Nonnull
	@Override
	public Class<OrderConstraint> getType() {
		return OrderConstraint.class;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

}
