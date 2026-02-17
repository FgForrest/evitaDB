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
 * The `facetGroupsDisjunction` requirement overrides the default **logical AND** relation that applies **between**
 * different facet groups or references and replaces it with **logical OR** (disjunction) for the specified reference
 * and relation level.
 *
 * By default, when a user selects facets from multiple different groups evitaDB requires that entities satisfy all
 * of them (AND): products must be *blue* **and** *large*. This constraint changes that behaviour for targeted groups
 * so that satisfying any one of the groups is sufficient: products that are *blue* **or** *large*.
 *
 * ## Arguments
 *
 * - **referenceName** *(mandatory)* — the name of the faceted reference this constraint applies to (e.g.
 *   `"parameterValues"`, `"brand"`)
 * - **facetGroupRelationLevel** *(optional, default `WITH_DIFFERENT_FACETS_IN_GROUP`)* — the level at which
 *   disjunction is applied:
 *   - `WITH_DIFFERENT_FACETS_IN_GROUP` — disjunction between individual facets within the same group (this is the
 *     **default behaviour** for within-group relations, so specifying this level for `facetGroupsDisjunction` is
 *     effectively a no-op unless it was previously set to conjunction)
 *   - `WITH_DIFFERENT_GROUPS` — disjunction between facets **across** different groups of this reference, overriding
 *     the default AND that normally applies between groups
 * - **filterBy** *(optional)* — a {@link FilterBy} constraint targeting properties of the **group entity** to select
 *   which groups the disjunction applies to; when omitted, disjunction applies to all groups of the reference
 *
 * ## Effect on impact calculations
 *
 * This constraint affects both actual filtering (when facets are selected inside `userFilter`) and the impact
 * predictions computed by {@link FacetSummary} / {@link FacetSummaryOfReference}. Switching from AND to OR across
 * groups typically **expands** the predicted result count, because the query becomes less restrictive.
 *
 * ## Relationship to FacetCalculationRules
 *
 * {@link FacetCalculationRules} can set the global default conjunction/disjunction behaviour for all references at
 * once. `facetGroupsDisjunction` takes precedence over that global default for the specific groups it targets.
 *
 * ## Example
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             facetHaving("parameterValues", entityPrimaryKeyInSet(11, 31))
 *         )
 *     ),
 *     require(
 *         facetSummary(IMPACT),
 *         facetGroupsDisjunction(
 *             "parameterValues",
 *             WITH_DIFFERENT_GROUPS,
 *             filterBy(attributeInSet("code", "color", "tags"))
 *         )
 *     )
 * )
 * ```
 *
 * With this setting, selecting `blue` from `color` and `new products` from `tags` returns products matching either
 * the colour **or** the tag — instead of the default AND that would require both.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
 *
 * @see FacetGroupRelationLevel
 * @see FacetGroupsConjunction
 * @see FacetGroupsNegation
 * @see FacetGroupsExclusivity
 * @see FacetCalculationRules
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "groupsDisjunction",
	shortDescription = "The constraint overrides facet relation to logical OR (disjunction) for specified reference groups, meaning any selected facet can match.",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-disjunction"
)
public class FacetGroupsDisjunction extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = 1087282346634617160L;

	private FacetGroupsDisjunction(
		@Nonnull Serializable[] arguments,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(
				child instanceof FilterBy,
				"Only FilterBy constraints are allowed in FacetGroupsDisjunction."
			);
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
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(ArrayUtils.isEmpty(children), "Children must be empty");
		return new FacetGroupsDisjunction(getArguments(), additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsDisjunction(newArguments, getAdditionalChildren());
	}
}
