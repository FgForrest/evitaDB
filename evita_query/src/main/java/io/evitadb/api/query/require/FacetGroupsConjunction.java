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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * This `facetGroupsConjunction` require allows specifying inter-facet relation inside facet groups of certain primary ids.
 * First mandatory argument specifies entity type of the facet group, secondary argument allows to define one more facet
 * group ids which inner facets should be considered conjunctive.
 *
 * This require constraint changes default behaviour stating that all facets inside same facet group are combined by OR
 * relation (eg. disjunction). Constraint has sense only when [facet](#facet) constraint is part of the query.
 *
 * Example:
 *
 * <pre>
 * query(
 *    entities("product"),
 *    filterBy(
 *       userFilter(
 *          facet("group", 1, 2),
 *          facet(
 *             "parameterType",
 *             entityPrimaryKeyInSet(11, 12, 22)
 *          )
 *       )
 *    ),
 *    require(
 *       facetGroupsConjunction("parameterType", 1, 8, 15)
 *    )
 * )
 * </pre>
 *
 * This statement means, that facets in `parameterType` groups `1`, `8`, `15` will be joined with boolean AND relation when
 * selected.
 *
 * Let's have this facet/group situation:
 *
 * Color `parameterType` (group id: 1):
 *
 * - blue (facet id: 11)
 * - red (facet id: 12)
 *
 * Size `parameterType` (group id: 2):
 *
 * - small (facet id: 21)
 * - large (facet id: 22)
 *
 * Flags `tag` (group id: 3):
 *
 * - action products (facet id: 31)
 * - new products (facet id: 32)
 *
 * When user selects facets: blue (11), red (12) by default relation would be: get all entities that have facet blue(11) OR
 * facet red(12). If require `facetGroupsConjunction('parameterType', 1)` is passed in the query filtering condition will
 * be composed as: blue(11) AND red(12)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "groupsConjunction",
	shortDescription = "Sets inter-facets relation within the specified groups to [logical AND](https://en.wikipedia.org/wiki/Logical_conjunction)."
)
public class FacetGroupsConjunction extends AbstractRequireConstraintContainer implements FacetConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -584073466325272463L;

	private FacetGroupsConjunction(@Nonnull Serializable[] arguments, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(child instanceof FilterBy, "Only FilterBy constraints are allowed in FacetGroupsConjunction.");
		}
	}

	@Creator
	public FacetGroupsConjunction(@Nonnull @Classifier String referenceName,
	                              @Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) FilterBy filterBy) {
		super(new Serializable[]{referenceName}, NO_CHILDREN, filterBy);
	}

	/**
	 * Returns name of the reference name this constraint relates to.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns filter constraint that can be resolved to array of facet groups primary keys.
	 */
	@AliasForParameter("filterBy")
	@Nonnull
	public Optional<FilterBy> getFacetGroups() {
		return Arrays.stream(getAdditionalChildren())
			.filter(child -> child instanceof FilterBy)
			.map(FilterBy.class::cast)
			.findAny();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsConjunction(newArguments, getAdditionalChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(ArrayUtils.isEmpty(children), "Children must be empty");
		return new FacetGroupsConjunction(getArguments(), additionalChildren);
	}
}
