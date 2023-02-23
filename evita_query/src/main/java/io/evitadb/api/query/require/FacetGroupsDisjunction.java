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
 * This `facetGroupsDisjunction` require constraint allows specifying facet relation among different facet groups of certain
 * primary ids. First mandatory argument specifies entity type of the facet group, secondary argument allows to define one
 * more facet group ids that should be considered disjunctive.
 *
 * This require constraint changes default behaviour stating that facets between two different facet groups are combined by
 * AND relation and changes it to the disjunction relation instead.
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
 *       facetGroupsDisjunction('parameterType', 1, 2)
 *    )
 * )
 * </pre>
 *
 * This statement means, that facets in `parameterType` facet groups `1`, `2` will be joined with the rest of the query by
 * boolean OR relation when selected.
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
 * When user selects facets: blue (11), large (22), new products (31) - the default meaning would be: get all entities that
 * have facet blue as well as facet large and action products tag (AND). If require `facetGroupsDisjunction('tag', 3)`
 * is passed in the query, filtering condition will be composed as: (`blue(11)` AND `large(22)`) OR `new products(31)`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "groupsDisjunction",
	shortDescription = "Sets relation of facets in the specified groups towards facets in different groups to [logical OR](https://en.wikipedia.org/wiki/Logical_disjunction) ."
)
public class FacetGroupsDisjunction extends AbstractRequireConstraintLeaf implements FacetConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 1087282346634617160L;

	private FacetGroupsDisjunction(Serializable... arguments) {
		super(arguments);
	}

	@ConstraintCreatorDef
	public FacetGroupsDisjunction(@Nonnull @ConstraintClassifierParamDef String referenceName,
	                              @Nonnull @ConstraintValueParamDef Integer... facetGroups) {
		super(concat(referenceName, facetGroups));
	}

	/**
	 * Returns name of the reference name this query relates to.
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
		return new FacetGroupsDisjunction(newArguments);
	}
}
