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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * The `entityPrimaryKeyBetween` constraint filters the returned entities to only those whose
 * primary key falls within the specified inclusive range. At least one of the bounds must be
 * non-null.
 *
 * Example:
 *
 * ```
 * entityPrimaryKeyBetween(10, 100)
 * ```
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-between">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "between",
	shortDescription = "The constraint limits the returned entities to those with a primary key within the specified inclusive range (both ends are inclusive).",
	userDocsLink = "/documentation/query/filtering/comparable#entity-primary-key-between",
	supportedIn = { ConstraintDomain.ENTITY },
	supportedValues = @ConstraintSupportedValues(supportedTypes = { Byte.class, Short.class, Integer.class, Long.class, BigDecimal.class })
)
public class EntityPrimaryKeyBetween extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 4185629307412856193L;

	private EntityPrimaryKeyBetween(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Creates a constraint that filters entities to those with a primary key within the specified
	 * inclusive range. At least one of the bounds must be non-null. Inputs may be any supported
	 * numeric type (Byte, Short, Integer, Long, BigDecimal) — they are converted to Integer via
	 * {@link EvitaDataTypes#toTargetType}, which throws if the value does not fit in an int.
	 *
	 * @param from the lower bound of the range (inclusive), or null for unbounded lower
	 * @param to   the upper bound of the range (inclusive), or null for unbounded upper
	 */
	@Creator(implicitClassifier = "primaryKey")
	public <T extends Number & Serializable> EntityPrimaryKeyBetween(
		@Nullable @Value(requiresPlainType = true) T from,
		@Nullable @Value(requiresPlainType = true) T to
	) {
		super(
			EvitaDataTypes.toTargetType(from, Integer.class),
			EvitaDataTypes.toTargetType(to, Integer.class)
		);
		Assert.isTrue(
			from != null || to != null,
			"At least one bound (from or to) must be non-null for entityPrimaryKeyBetween."
		);
	}

	/**
	 * Returns the lower bound of the primary key range (inclusive), or null if unbounded.
	 */
	@Nullable
	public Integer getFrom() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns the upper bound of the primary key range (inclusive), or null if unbounded.
	 */
	@Nullable
	public Integer getTo() {
		return (Integer) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length == 2 && (getFrom() != null || getTo() != null);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyBetween(newArguments);
	}
}
