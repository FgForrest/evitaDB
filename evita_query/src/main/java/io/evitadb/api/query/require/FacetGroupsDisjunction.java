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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintWithDefaults;
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
 * This `facetGroupsDisjunction` require constraint allows specifying facet relation on particular level (within group
 * or with different group facets) of certain primary ids. First mandatory argument specifies entity type of the facet
 * group, secondary optional argument allows defining the level for which the disjunction is defined, third optional
 * argument defines one more facet group ids which facets should be considered disjunctive with other facets either
 * in same group or different groups (depending on level argument).
 *
 * This require constraint changes default behavior of the facet calculation rules.
 * Constraint has sense only when [facet](#facet) constraint is part of the query.
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
 *       facetGroupsDisjunction("parameterType", WITH_DIFFERENT_GROUPS, 1, 2)
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
 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction">Visit detailed user documentation</a></p>
 *
 * @see FacetGroupRelationLevel
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "groupsDisjunction",
	shortDescription = "Sets facet relation on particular level (within group or with different group facets) to [logical OR](https://en.wikipedia.org/wiki/Logical_disjunction) .",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-disjunction"
)
public class FacetGroupsDisjunction extends AbstractRequireConstraintContainer implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = 1087282346634617160L;

	private FacetGroupsDisjunction(@Nonnull Serializable[] arguments, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(child instanceof FilterBy, "Only FilterBy constraints are allowed in FacetGroupsDisjunction.");
		}
	}

	public FacetGroupsDisjunction(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		this(referenceName, null, filterBy);
	}

	@Creator
	public FacetGroupsDisjunction(
		@Nonnull @Classifier String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel,
		@Nullable @AdditionalChild(domain = ConstraintDomain.GROUP_ENTITY) FilterBy filterBy
	) {
		super(
			facetGroupRelationLevel == null ?
				new Serializable[]{referenceName, FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP} :
				new Serializable[]{referenceName, facetGroupRelationLevel},
			NO_CHILDREN,
			filterBy
		);
	}

	@Override
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Override
	@AliasForParameter("relation")
	@Nonnull
	public FacetGroupRelationLevel getFacetGroupRelationLevel() {
		return Arrays.stream(getArguments())
			.filter(FacetGroupRelationLevel.class::isInstance)
			.map(FacetGroupRelationLevel.class::cast)
			.findFirst()
			.orElse(FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP);
	}

	@Override
	@AliasForParameter("filterBy")
	@Nonnull
	public Optional<FilterBy> getFacetGroups() {
		return Arrays.stream(getAdditionalChildren())
			.filter(FilterBy.class::isInstance)
			.map(FilterBy.class::cast)
			.findAny();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		if (getFacetGroupRelationLevel() == FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP) {
			return new Serializable[]{ getReferenceName() };
		} else {
			return super.getArguments();
		}
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		if (getFacetGroupRelationLevel() == FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP) {
			return !(serializable instanceof String);
		}
		return false;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(ArrayUtils.isEmpty(children), "Children must be empty");
		return new FacetGroupsDisjunction(getArguments(), additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsDisjunction(newArguments, getAdditionalChildren());
	}
}
