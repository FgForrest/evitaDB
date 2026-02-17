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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint allows to sort output entities by primary key values in the exact order that is specified in
 * the arguments of this constraint.
 *
 * Example usage:
 *
 * ```evitaql
 * query(
 *    filterBy(
 *       attributeEqualsTrue("shortcut")
 *    ),
 *    orderBy(
 *       entityPrimaryKeyExact(5, 1, 8)
 *    )
 * )
 * ```
 *
 * The example will return the selected entities (if present) in the exact order that is stated in the argument of
 * this ordering constraint. If there are entities, whose primary keys are not present in the argument, then they
 * will be present at the end of the output in ascending order of their primary keys (or they will be sorted by
 * additional ordering constraint in the chain).
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "exact",
	shortDescription = "Sorts returned entities by primary key in the exact order specified in the constraint arguments.",
	userDocsLink = "/documentation/query/ordering/constant#exact-entity-primary-key-order",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class EntityPrimaryKeyExact extends AbstractOrderConstraintLeaf implements EntityConstraint<OrderConstraint> {
	@Serial
	private static final long serialVersionUID = -8627803791652731430L;

	private EntityPrimaryKeyExact(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyExact(@Nonnull Integer... primaryKeys) {
		super(primaryKeys);
	}

	/**
	 * Returns primary keys to sort along.
	 */
	@Nonnull
	public int[] getPrimaryKeys() {
		final Serializable[] args = getArguments();
		final int[] result = new int[args.length];
		for (int i = 0; i < args.length; i++) {
			result[i] = (Integer) args[i];
		}
		return result;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyExact(newArguments);
	}
}
