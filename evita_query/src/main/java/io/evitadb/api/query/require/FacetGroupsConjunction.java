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

import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

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
 *    entities('product'),
 *    filterBy(
 *       userFilter(
 *          facet('group', 1, 2),
 *          facet('parameterType', 11, 12, 22)
 *       )
 *    ),
 *    require(
 *       facetGroupsConjunction('parameterType', 1, 8, 15)
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
@ConstraintDef(
	name = "groupsConjunction",
	shortDescription = "Sets inter-facets relation within the specified groups to [logical AND](https://en.wikipedia.org/wiki/Logical_conjunction)."
)
public class FacetGroupsConjunction extends AbstractRequireConstraintLeaf implements FacetConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -584073466325272463L;

	private FacetGroupsConjunction(Serializable... arguments) {
		super(arguments);
	}

	@ConstraintCreatorDef
	public FacetGroupsConjunction(@Nonnull @ConstraintClassifierParamDef String referenceName,
	                              @Nonnull @ConstraintValueParamDef Integer... facetGroups) {
		super(concat(referenceName, facetGroups));
	}

	/**
	 * Returns name of the reference name this constraint relates to.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns ids of facet groups.
	 */
	public int[] getFacetGroups() {
		return Arrays.stream(getArguments())
			.skip(1)
			.mapToInt(Integer.class::cast)
			.toArray();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsConjunction(newArguments);
	}
}
