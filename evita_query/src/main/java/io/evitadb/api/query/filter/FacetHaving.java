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
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `facetHaving` filtering constraint is typically placed inside the {@link UserFilter} constraint container and
 * represents the user's request to drill down the result set by a particular facet. The `facetHaving` constraint works
 * exactly like the referenceHaving constraint, but works in conjunction with the facetSummary requirement to correctly
 * calculate the facet statistics and impact predictions. When used outside the userFilter constraint container,
 * the `facetHaving` constraint behaves like the {@link ReferenceHaving} constraint.
 *
 * Example:
 *
 * <pre>
 * userFilter(
 *   facetHaving(
 *     "brand",
 *     entityHaving(
 *       attributeInSet("code", "amazon")
 *     )
 *   )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/references#facet-having">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container allowing to filter entities by having references to entities managed by evitaDB that " +
		"match the inner filter constraint. This container resembles the SQL inner join clauses and works in cooperation " +
		"with facet summary requirement.",
	userDocsLink = "/documentation/query/filtering/references#facet-having",
	supportedIn = ConstraintDomain.ENTITY
)
public class FacetHaving extends AbstractFilterConstraintContainer implements FacetConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -4135466525683422992L;

	private FacetHaving(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	/**
	 * Private constructor that creates unnecessary / not applicable version of the query.
	 */
	private FacetHaving(@Nonnull @Classifier String referenceName) {
		super(referenceName);
	}

	@Creator
	public FacetHaving(@Nonnull @Classifier String referenceName,
	                   @Nonnull @Child(uniqueChildren = true) FilterConstraint... filter) {
		super(new Serializable[]{referenceName}, filter);
	}

	/**
	 * Returns reference name of the facet relation that should be used for applying for filtering according to children constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length == 1 && getChildren().length > 0;
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
		return children.length == 0 ? new FacetHaving(getReferenceName()) : new FacetHaving(getReferenceName(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetHaving(newArguments, getChildren());
	}
}
