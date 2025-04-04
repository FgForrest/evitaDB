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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `entityHaving` constraint is used to examine the attributes or other filterable properties of the referenced
 * entity. It can only be used within the referenceHaving constraint, which defines the name of the entity reference
 * that identifies the target entity to be subjected to the filtering restrictions in the entityHaving constraint.
 * The filtering constraints for the entity can use entire range of filtering operators.
 *
 * Example:
 *
 * <pre>
 * referenceHaving(
 *     "brand",
 *     entityHaving(
 *         attributeEquals("code", "apple")
 *     )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/references#entity-having">Visit detailed user documentation</a></p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container allowing to filter entities by having references to entities managed by evitaDB that " +
		"match inner filtering constraints. This container resembles the SQL inner join clauses where the `entityHaving`" +
		"contains the filtering condition on particular join.",
	userDocsLink = "/documentation/query/filtering/references#entity-having",
	supportedIn = { ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET, ConstraintDomain.SEGMENT }
)
public class EntityHaving extends AbstractFilterConstraintContainer implements EntityConstraint<FilterConstraint>, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = 7151549459608672988L;

	private EntityHaving() {}

	@Creator
	public EntityHaving(@Nonnull FilterConstraint child) {
		super(child);
	}

	@Nullable
	public FilterConstraint getChild() {
		final FilterConstraint[] children = getChildren();
		return children.length > 0 ? children[0] : null;
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"EntityHaving cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"EntityHaving can have only one child."
		);
		return children.length == 0 ? new EntityHaving() : new EntityHaving(children[0]);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("EntityHaving filtering constraint has no arguments!");
	}

}
