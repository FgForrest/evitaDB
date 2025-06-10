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
 * This `facetGroupsExclusivity` require constraint allows specifying facet relation on particular level (within group
 * or with different group facets) of certain primary ids. First mandatory argument specifies entity type of the facet
 * group, secondary optional argument allows defining the level for which the exclusivity is defined, third optional
 * argument defines one more facet group ids which facets should be considered exclusive with other facets either
 * in same group or different groups (depending on level argument).
 *
 * The `facetGroupsExclusivity` changes the behavior of the facet option in all facet groups specified in the filterBy
 * constraint (or all). Query will allow selecting only single facet from either the group or facets in single group
 * among other groups depending on the level argument:
 *
 * - WITH_DIFFERENT_GROUPS: when facets are selected, they must always be from single group (within the relation only)
 * - WITH_DIFFERENT_FACETS_IN_GROUP: only single facet can be selected from each group (within the relation only)
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummaryOfReference(
 *             "parameterValues",
 *             IMPACT,
 *             filterBy(attributeContains("code", "4")),
 *             filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
 *             entityFetch(attributeContent("code")),
 *             entityGroupFetch(attributeContent("code"))
 *         ),
 *         facetGroupsExclusivity(
 *             "parameterValues",
 *             WITH_DIFFERENT_FACETS_IN_GROUP,
 *             filterBy(
 *               attributeInSet("code", "ram-memory")
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * In group `ram-memory` only single facet can be selected, so it should be probably rendered as radio buttons in the UI
 * instead of checkboxes.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity">Visit detailed user documentation</a></p>
 *
 * @see FacetGroupRelationLevel
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "groupsExclusivity",
	shortDescription = "Sets facet relation on particular level (within group or with different group facets) to exclusive - only one facet can be selected.",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-exclusivity"
)
public class FacetGroupsExclusivity extends AbstractRequireConstraintContainer implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = 849094126558825930L;

	private FacetGroupsExclusivity(@Nonnull Serializable[] arguments, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(child instanceof FilterBy, "Only FilterBy constraints are allowed in FacetGroupsConjunction.");
		}
	}

	public FacetGroupsExclusivity(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		this(referenceName, null, filterBy);
	}

	@Creator
	public FacetGroupsExclusivity(
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
		return new FacetGroupsExclusivity(getArguments(), additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsExclusivity(newArguments, getAdditionalChildren());
	}

}