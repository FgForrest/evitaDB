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

package io.evitadb.api.query.require;


import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `facetCalculationRules` require constraint allows specifying default facet relation for each recognized level.
 * If this constraint is not present, the default behavior is to use the disjunction relation (logical OR) for facets
 * within the same group and conjunction relation (logical AND) for facets among different groups or relations.
 *
 * By using this constraint, you can change the default behavior of the facet calculation rules. The constraint accepts
 * two arguments:
 *
 * - `facetsWithSameGroup`: type of relation that should be applied on facets within the same group by default.
 * - `facetsWithDifferentGroups`: type of relation that should be applied on facets among different groups or relations by default.
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     require(
 *         facetCalculationRules(CONJUNCTION, EXCLUSIVITY)
 *     )
 * )
 * </pre>
 *
 * This query will change the default behavior of the facet calculation rules (only for this particular query) to use
 * the conjunction relation for facets within the same group and exclusivity relation for facets among different groups.
 * It is the equivalent for using the `facetGroupsConjunction` and `facetGroupsExclusivity` require constraints together
 * without specifying filter for the particular facet groups.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-calculation-rules">Visit detailed user documentation</a></p>
 *
 * @see FacetRelationType
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "calculationRules",
	shortDescription = "Allows specifying default facet relation for each recognized level.",
	userDocsLink = "/documentation/query/requirements/facet#facet-calculation-rules"
)
public class FacetCalculationRules extends AbstractRequireConstraintLeaf implements FacetConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5431884195676521481L;

	private FacetCalculationRules(@Nonnull Serializable[] arguments) {
		super(arguments);
	}

	@Creator
	public FacetCalculationRules(
		@Nullable FacetRelationType facetsWithSameGroup,
		@Nullable FacetRelationType facetsWithDifferentGroups
	) {
		super(
			facetsWithSameGroup != null ? facetsWithSameGroup : FacetRelationType.DISJUNCTION,
			facetsWithDifferentGroups != null ? facetsWithDifferentGroups : FacetRelationType.CONJUNCTION);
	}

	/**
	 * Returns type of relation that should be applied on facets within the same group by default.
	 */
	@AliasForParameter("facetsWithSameGroup")
	@Nonnull
	public FacetRelationType getFacetsWithSameGroupRelationType() {
		return (FacetRelationType) getArguments()[0];
	}

	/**
	 * Returns type of relation that should be applied on facets among different groups or relations by default.
	 */
	@AliasForParameter("facetsWithDifferentGroups")
	@Nonnull
	public FacetRelationType getFacetsWithDifferentGroupsRelationType() {
		return (FacetRelationType) getArguments()[1];
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(newArguments.length == 2, "FacetCalculationRules requires exactly two arguments.");
		Assert.isPremiseValid(Arrays.stream(newArguments).allMatch(FacetGroupRelationLevel.class::isInstance), "All arguments must be of type FacetGroupRelationLevel.");
		return new FacetCalculationRules(newArguments);
	}

}