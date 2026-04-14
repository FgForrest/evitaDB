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
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * The `entityPrimaryKeyGreaterThanEquals` constraint filters the returned entities to only those
 * whose primary key is greater than or equal to the specified value.
 *
 * Example:
 *
 * ```
 * entityPrimaryKeyGreaterThanEquals(100)
 * ```
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-greater-than-equals">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "greaterThanEquals",
	shortDescription = "The constraint limits the returned entities to those with a primary key greater than or equal to the specified value.",
	userDocsLink = "/documentation/query/filtering/comparable#entity-primary-key-greater-than-equals",
	supportedIn = { ConstraintDomain.ENTITY },
	supportedValues = @ConstraintSupportedValues(supportedTypes = { Byte.class, Short.class, Integer.class, Long.class, BigDecimal.class })
)
public class EntityPrimaryKeyGreaterThanEquals extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 7619204835172658491L;

	private EntityPrimaryKeyGreaterThanEquals(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Creates a constraint that filters entities to those with a primary key greater than or equal
	 * to the specified value. The input may be any supported numeric type (Byte, Short, Integer,
	 * Long, BigDecimal) — it is converted to Integer via {@link EvitaDataTypes#toTargetType}, which
	 * throws if the value does not fit in an int.
	 *
	 * @param primaryKey the threshold primary key value (inclusive lower bound)
	 */
	@Creator(implicitClassifier = "primaryKey")
	public <T extends Number & Serializable> EntityPrimaryKeyGreaterThanEquals(
		@Nonnull @Value(requiresPlainType = true) T primaryKey
	) {
		super(EvitaDataTypes.toTargetType(primaryKey, Integer.class));
	}

	/**
	 * Returns the primary key threshold value. Only entities with a primary key greater than or
	 * equal to this value will be included in the result.
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
		return new EntityPrimaryKeyGreaterThanEquals(newArguments);
	}
}
