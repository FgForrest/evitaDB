/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * The `entityPrimaryKeyLessThan` constraint filters the returned entities to only those whose
 * primary key is strictly less than the specified value.
 *
 * Example:
 *
 * ```
 * entityPrimaryKeyLessThan(100)
 * ```
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-less-than">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "lessThan",
	shortDescription = "The constraint limits the returned entities to those with a primary key less than the specified value.",
	userDocsLink = "/documentation/query/filtering/comparable#entity-primary-key-less-than",
	supportedIn = { ConstraintDomain.ENTITY },
	supportedValues = @ConstraintSupportedValues(supportedTypes = { Byte.class, Short.class, Integer.class, Long.class, BigDecimal.class })
)
public class EntityPrimaryKeyLessThan extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 2918473650184927563L;

	private EntityPrimaryKeyLessThan(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Creates a constraint that filters entities to those with a primary key strictly less than
	 * the specified value.
	 *
	 * @param primaryKey the threshold primary key value (exclusive upper bound)
	 */
	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyLessThan(
		@Nonnull @Value(requiresPlainType = true) Integer primaryKey
	) {
		super(primaryKey);
	}

	/**
	 * Returns the primary key threshold value. Only entities with a primary key strictly less
	 * than this value will be included in the result.
	 */
	public int getPrimaryKey() {
		return (int) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyLessThan(newArguments);
	}
}
