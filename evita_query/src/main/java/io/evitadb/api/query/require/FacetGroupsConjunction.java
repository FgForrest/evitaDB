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
 * The `facetGroupsConjunction` requirement overrides the default **logical OR** relation between facets within a
 * group (or across groups) and replaces it with **logical AND** (conjunction) for the specified reference and
 * relation level.
 *
 * By default, when a user selects multiple facets within the same group evitaDB treats them as alternatives (OR):
 * show products that are *blue* **or** *red*. This constraint changes that behaviour for targeted groups so that
 * all selected facets must match simultaneously: show products that are *blue* **and** *red*.
 *
 * ## Arguments
 *
 * - **referenceName** *(mandatory)* — the name of the faceted reference this constraint applies to (e.g.
 *   `"parameterValues"`, `"brand"`)
 * - **facetGroupRelationLevel** *(optional, default `WITH_DIFFERENT_FACETS_IN_GROUP`)* — the level at which
 *   conjunction is applied:
 *   - `WITH_DIFFERENT_FACETS_IN_GROUP` — conjunction between individual facets **within** the same group
 *   - `WITH_DIFFERENT_GROUPS` — conjunction between facets **across** different groups of this reference
 * - **filterBy** *(optional)* — a {@link FilterBy} constraint targeting properties of the **group entity** to select
 *   which groups the conjunction applies to; when omitted, conjunction applies to all groups of the reference
 *
 * ## Effect on impact calculations
 *
 * This constraint affects both actual filtering (when facets are selected inside `userFilter`) and the impact
 * predictions in the {@link FacetSummary} / {@link FacetSummaryOfReference} extra result. Switching from OR to AND
 * within a group typically **reduces** the predicted result count, because the query becomes more restrictive.
 *
 * ## Relationship to FacetCalculationRules
 *
 * {@link FacetCalculationRules} can set the global default conjunction/disjunction behaviour for all references at
 * once. `facetGroupsConjunction` takes precedence over that global default for the specific groups it targets.
 *
 * ## Example
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             facetHaving("parameterValues", entityPrimaryKeyInSet(11, 12))
 *         )
 *     ),
 *     require(
 *         facetSummary(IMPACT),
 *         facetGroupsConjunction(
 *             "parameterValues",
 *             WITH_DIFFERENT_FACETS_IN_GROUP,
 *             filterBy(attributeInSet("code", "color"))
 *         )
 *     )
 * )
 * ```
 *
 * With this setting, selecting both `blue` (id 11) and `red` (id 12) from the `color` group returns only products
 * that have **both** colour attributes — instead of the default behaviour of returning products with either.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
 *
 * @see FacetGroupRelationLevel
 * @see FacetGroupsDisjunction
 * @see FacetGroupsNegation
 * @see FacetGroupsExclusivity
 * @see FacetCalculationRules
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "groupsConjunction",
	shortDescription = "The constraint overrides facet relation to logical AND (conjunction) for specified reference groups, meaning all selected facets must match.",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-conjunction"
)
public class FacetGroupsConjunction extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = -584073466325272463L;

	private FacetGroupsConjunction(
		@Nonnull Serializable[] arguments,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(
				child instanceof FilterBy,
				"Only FilterBy constraints are allowed in FacetGroupsConjunction."
			);
		}
	}

	public FacetGroupsConjunction(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		this(referenceName, null, filterBy);
	}

	@Creator
	public FacetGroupsConjunction(
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
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsConjunction(newArguments, getAdditionalChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(ArrayUtils.isEmpty(children), "Children must be empty");
		return new FacetGroupsConjunction(getArguments(), additionalChildren);
	}
}
