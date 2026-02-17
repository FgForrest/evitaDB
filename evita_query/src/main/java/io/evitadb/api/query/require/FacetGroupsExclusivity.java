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
 * The `facetGroupsExclusivity` requirement marks facet options in the specified groups as **mutually exclusive**:
 * at most one facet may be selected at the applicable level at any given time. This constraint primarily affects the
 * **impact predictions** computed by {@link FacetSummary} / {@link FacetSummaryOfReference} — it tells the engine to
 * treat previous selections as implicitly deselected when predicting the result count for a new selection.
 *
 * The practical effect is that the impact prediction for each facet option in an exclusive group shows "how many
 * results you would get if you replaced your current selection with this option" rather than "how many results you
 * would get if you added this option to your current selection".
 *
 * ## UI implication
 *
 * Groups marked as exclusive are naturally represented in the UI as **radio buttons** (select one) rather than
 * checkboxes (select many), since only a single selection is meaningful.
 *
 * ## Arguments
 *
 * - **referenceName** *(mandatory)* — the name of the faceted reference this constraint applies to (e.g.
 *   `"parameterValues"`, `"brand"`)
 * - **facetGroupRelationLevel** *(optional, default `WITH_DIFFERENT_FACETS_IN_GROUP`)* — the level at which
 *   exclusivity is enforced:
 *   - `WITH_DIFFERENT_FACETS_IN_GROUP` — only one facet can be selected **within** each group; other groups are
 *     independent
 *   - `WITH_DIFFERENT_GROUPS` — only one group's facet can be selected at a time **across** the affected groups of
 *     this reference
 * - **filterBy** *(optional)* — a {@link FilterBy} constraint targeting properties of the **group entity** to select
 *   which groups exclusivity applies to; when omitted, exclusivity applies to all groups of the reference
 *
 * ## Relationship to FacetCalculationRules
 *
 * {@link FacetCalculationRules} can set the global default relation type including `EXCLUSIVITY`. `facetGroupsExclusivity`
 * takes precedence over that global default for the specific groups it targets.
 *
 * ## Example
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummaryOfReference(
 *             "parameterValues",
 *             IMPACT,
 *             filterBy(attributeContains("code", "memory")),
 *             filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
 *             entityFetch(attributeContent("code")),
 *             entityGroupFetch(attributeContent("code"))
 *         ),
 *         facetGroupsExclusivity(
 *             "parameterValues",
 *             WITH_DIFFERENT_FACETS_IN_GROUP,
 *             filterBy(attributeInSet("code", "ram-memory"))
 *         )
 *     )
 * )
 * ```
 *
 * With this setting the `ram-memory` group shows impact predictions as if selecting a new RAM option deselects the
 * previously selected one. The group should be rendered with radio buttons in the client UI.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
 *
 * @see FacetGroupRelationLevel
 * @see FacetGroupsConjunction
 * @see FacetGroupsDisjunction
 * @see FacetGroupsNegation
 * @see FacetCalculationRules
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "groupsExclusivity",
	shortDescription = "The constraint marks facets in specified reference groups as mutually exclusive — at most one facet may be selected at a time.",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-exclusivity"
)
public class FacetGroupsExclusivity extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = 849094126558825930L;

	private FacetGroupsExclusivity(
		@Nonnull Serializable[] arguments,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(
				child instanceof FilterBy,
				"Only FilterBy constraints are allowed in FacetGroupsExclusivity."
			);
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
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(ArrayUtils.isEmpty(children), "Children must be empty");
		return new FacetGroupsExclusivity(getArguments(), additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsExclusivity(newArguments, getAdditionalChildren());
	}

}