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

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `primaryKey` is query that accepts set of {@link Integer}
 * that represents primary keys of the entities that should be returned.
 *
 * Function returns true if entity primary key is part of the passed set of integers.
 * This form of entity lookup function is the fastest one.
 *
 * Only single `primaryKey` query can be used in the query.
 *
 * Example:
 *
 * ```
 * primaryKey(1, 2, 3)
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inSet",
	shortDescription = "The constraint if primary key of the entity equals to at least one of the passed values. " +
		"The constraint is equivalent to one or more `equals` constraints combined with logical OR.",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
)
public class EntityPrimaryKeyInSet extends AbstractFilterConstraintLeaf implements EntityConstraint<FilterConstraint>, IndexUsingConstraint {
	@Serial private static final long serialVersionUID = -6950287451642746676L;

	private EntityPrimaryKeyInSet(Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyInSet(@Nonnull @Value Integer... primaryKey) {
		super(primaryKey);
	}

	/**
	 * Returns primary keys of entities to lookup for.
	 */
	@Nonnull
	public int[] getPrimaryKeys() {
		return Arrays.stream(getArguments())
			.mapToInt(Integer.class::cast)
			.toArray();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyInSet(newArguments);
	}
}
