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
 * The `facetGroupsNegation` requirement **inverts** the meaning of facet selection for the specified groups of a
 * reference. Instead of returning entities that **have** a reference to the selected facet entity, the query returns
 * entities that **do not have** a reference to it — a logical NOT (negation).
 *
 * This is the evitaDB equivalent of "show me everything *except* products with this property", which is useful for
 * filtering out unwanted characteristics (e.g. "exclude discontinued items", "exclude out-of-stock sizes").
 *
 * ## Arguments
 *
 * - **referenceName** *(mandatory)* — the name of the faceted reference this constraint applies to (e.g.
 *   `"parameterValues"`, `"brand"`)
 * - **facetGroupRelationLevel** *(optional, default `WITH_DIFFERENT_FACETS_IN_GROUP`)* — the level at which
 *   negation is applied:
 *   - `WITH_DIFFERENT_FACETS_IN_GROUP` — negation applies to individual facets within the same group; each selected
 *     facet independently excludes matching entities
 *   - `WITH_DIFFERENT_GROUPS` — negation applies at the group level, across different groups of this reference
 * - **filterBy** *(optional)* — a {@link FilterBy} constraint targeting properties of the **group entity** to select
 *   which groups negation applies to; when omitted, negation applies to all groups of the reference
 *
 * ## Effect on impact calculations
 *
 * This constraint affects both actual filtering (when facets are selected inside `userFilter`) and the impact
 * predictions computed by {@link FacetSummary} / {@link FacetSummaryOfReference}. Because negated groups effectively
 * exclude a subset of entities, predicted result counts for negated facets are typically **much larger** than counts
 * produced by the default inclusive behaviour — the predicted count represents "how many results remain after
 * excluding this facet", which is often close to the full result set.
 *
 * ## Relationship to FacetCalculationRules
 *
 * {@link FacetCalculationRules} can set the global default relation type including `NEGATION`. `facetGroupsNegation`
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
 *         facetGroupsNegation(
 *             "parameterValues",
 *             WITH_DIFFERENT_GROUPS,
 *             filterBy(attributeInSet("code", "ram-memory"))
 *         )
 *     )
 * )
 * ```
 *
 * With this setting, selecting a RAM memory option predicts returning products that do **not** have that RAM size.
 * The predicted counts will be high (most products lack a specific RAM value), while the ROM group with default
 * behaviour predicts only a small subset.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation)
 *
 * @see FacetGroupRelationLevel
 * @see FacetGroupsConjunction
 * @see FacetGroupsDisjunction
 * @see FacetGroupsExclusivity
 * @see FacetCalculationRules
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "groupsNegation",
	shortDescription = "The constraint negates facet selection for specified reference groups — selecting a facet returns entities that do NOT have that facet.",
	userDocsLink = "/documentation/query/requirements/facet#facet-groups-negation"
)
public class FacetGroupsNegation extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetGroupsConstraint {
	@Serial private static final long serialVersionUID = 3993873252481237893L;

	private FacetGroupsNegation(
		@Nonnull Serializable[] arguments,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, NO_CHILDREN, additionalChildren);
		for (Constraint<?> child : additionalChildren) {
			Assert.isPremiseValid(
				child instanceof FilterBy,
				"Only FilterBy constraints are allowed in FacetGroupsNegation."
			);
		}
	}

	public FacetGroupsNegation(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		this(referenceName, null, filterBy);
	}

	@Creator
	public FacetGroupsNegation(
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

	@Nonnull
	@Override
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@AliasForParameter("relation")
	@Nonnull
	@Override
	public FacetGroupRelationLevel getFacetGroupRelationLevel() {
		return Arrays.stream(getArguments())
			.filter(FacetGroupRelationLevel.class::isInstance)
			.map(FacetGroupRelationLevel.class::cast)
			.findFirst()
			.orElse(FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP);
	}

	@AliasForParameter("filterBy")
	@Nonnull
	@Override
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
		return new FacetGroupsNegation(getArguments(), additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetGroupsNegation(newArguments, getAdditionalChildren());
	}

}
