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

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint allows to sort output entities by primary key values in the exact order that was used for filtering
 * them. The constraint requires presence of exactly one {@link EntityPrimaryKeyInSet} constraint in filter part of
 * the query. It uses {@link EntityPrimaryKeyInSet#getPrimaryKeys()} array for sorting the output of the query.
 *
 * Example usage:
 *
 * ```evitaql
 * query(
 *    filterBy(
 *       entityPrimaryKeyInSet(5, 1, 8)
 *    ),
 *    orderBy(
 *       entityPrimaryKeyInFilter()
 *    )
 * )
 * ```
 *
 * The example will return the selected entities (if present) in the exact order that was used for array filtering them.
 * The ordering constraint is particularly useful when you have sorted set of entity primary keys from an external
 * system which needs to be maintained (for example, it represents a relevancy of those entities).
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order-used-in-filter)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inFilter",
	shortDescription = "Sorts returned entities by primary key in the exact order used for filtering them via the primary-key-in-set filter constraint.",
	userDocsLink = "/documentation/query/ordering/constant#exact-entity-primary-key-order-used-in-filter",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class EntityPrimaryKeyInFilter extends AbstractOrderConstraintLeaf implements EntityConstraint<OrderConstraint> {
	@Serial
	private static final long serialVersionUID = 118454118196750296L;

	private EntityPrimaryKeyInFilter(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyInFilter() {
		super();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			ArrayUtils.isEmpty(newArguments),
			"Ordering constraint EntityPrimaryKeyInFilter doesn't accept arguments."
		);
		return new EntityPrimaryKeyInFilter();
	}
}
