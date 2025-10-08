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

package io.evitadb.api.query.order;

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint allows to sort output entities by primary key values in the exact order.
 *
 * Example usage:
 *
 * <pre>
 * query(
 *    orderBy(
 *       entityPrimaryKeyNatural(DESC)
 *    )
 * )
 * </pre>
 *
 * The example will return the selected entities (if present) in the descending order of their primary keys. Since
 * the entities are by default ordered by their primary key in ascending order, it has no sense to use this constraint
 * with {@link OrderDirection#ASC} direction.
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/comparable#primary-key-natural">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "natural",
	shortDescription = "The constraint sorts returned entities by primary key in specific order.",
	userDocsLink = "/documentation/query/ordering/comparable#primary-key-natural",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class EntityPrimaryKeyNatural extends AbstractOrderConstraintLeaf implements EntityConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -3469549716371304530L;

	private EntityPrimaryKeyNatural(Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyNatural(@Nonnull OrderDirection orderDirection) {
		super(orderDirection);
	}

	/**
	 * Returns order direction of how the attribute values must be sorted.
	 */
	@Nonnull
	public OrderDirection getOrderDirection() {
		return (OrderDirection) getArguments()[0];
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyNatural(newArguments);
	}
}
