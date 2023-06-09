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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `referenceAttribute` container is filtering query that filters returned entities by their reference
 * attributes that must match the inner condition.
 *
 * Example:
 *
 * ```
 * referenceHavingAttribute(
 * 'CATEGORY',
 * eq('code', 'KITCHENWARE')
 * )
 * ```
 *
 * or
 *
 * ```
 * referenceHavingAttribute(
 * 'CATEGORY',
 * and(
 * isTrue('visible'),
 * eq('code', 'KITCHENWARE')
 * )
 * )
 * ```
 *
 * TOBEDONE JNO - consider renaming to `referenceMatching`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container allowing to filter entities by having references to entities managed by evitaDB that " +
		"match any of the passed entity primary keys. This container resembles the SQL inner join clauses.",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceHaving extends AbstractFilterConstraintContainer implements ReferenceConstraint<FilterConstraint>, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = -2727265686254207631L;

	private ReferenceHaving(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	/**
	 * Private constructor that creates unnecessary / not applicable version of the query.
	 */
	private ReferenceHaving(@Nonnull @Classifier String referenceName) {
		super(referenceName);
	}

	@Creator
	public ReferenceHaving(@Nonnull @Classifier String referenceName,
	                       @Nonnull @Child FilterConstraint... filter) {
		super(new Serializable[]{referenceName}, filter);
	}

	/**
	 * Returns reference name of the relation that should be used for applying for filtering according to children constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length == 1 && getChildren().length == 1;
	}

	@AliasForParameter("filter")
	@Nonnull
	@Override
	public FilterConstraint[] getChildren() {
		return super.getChildren();
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return children.length == 0 ? new ReferenceHaving(getReferenceName()) : new ReferenceHaving(getReferenceName(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceHaving(newArguments, getChildren());
	}
}
