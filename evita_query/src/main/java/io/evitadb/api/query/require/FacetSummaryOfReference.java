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
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * The `facetSummaryOfReference` requirement triggers the calculation of the facet summary for a **single named
 * reference**, overriding all corresponding constraints that would otherwise come from a generic {@link FacetSummary}
 * present in the same `require()` container. When both constraints appear together, the generic `facetSummary` defines
 * the baseline for every faceted reference, while each `facetSummaryOfReference` **completely replaces** that baseline
 * for the reference it targets — the constraints are never merged.
 *
 * This constraint can also stand alone (without a generic `facetSummary`) when you only want statistics for a single
 * specific reference.
 *
 * ## Targeting a reference
 *
 * The first mandatory argument is the reference name as defined in the entity schema (e.g. `"parameterValues"`,
 * `"brand"`). The constraint only produces output for that reference; all other faceted references in the schema are
 * unaffected unless they have their own `facetSummaryOfReference`.
 *
 * ## Statistics depth
 *
 * The second argument selects the depth of statistics computed for each facet option:
 *
 * - `COUNTS` *(default, implicit)* — count of matching entities only
 * - `IMPACT` — additionally predicts how many results would remain if the user selected that facet
 *
 * ## Filtering and ordering
 *
 * Because this constraint targets exactly one reference type, its filter and order constraints can reference
 * **any filterable or sortable property of that specific referenced entity**, not just properties shared across all
 * referenced entity types. This is the primary reason to use `facetSummaryOfReference` instead of (or alongside) the
 * generic `facetSummary`:
 *
 * - {@link FilterBy} — restricts which individual facet options appear in the summary
 * - {@link FilterGroupBy} — restricts which facet groups appear in the summary
 * - {@link OrderBy} — controls the sort order of facet options within each group
 * - {@link OrderGroupBy} — controls the sort order of groups within the reference
 *
 * All filter and order constraints target properties on the **referenced entity** (facet entity or group entity);
 * they cannot reference attributes on the reference relation itself.
 *
 * ## Entity fetch requirements
 *
 * You can attach at most one {@link EntityFetch} (controls data loaded for facet entities) and at most one
 * {@link EntityGroupFetch} (controls data loaded for group entities). Providing two constraints of the same type
 * throws an exception at construction time.
 *
 * ## Default calculation rules
 *
 * Unless overridden by {@link FacetCalculationRules} or per-group behavior constraints, the same defaults apply as
 * for `facetSummary`:
 *
 * 1. Only entities in the current query result are counted.
 * 2. Filters outside `userFilter` are always respected.
 * 3. Facets within the same group are combined with logical OR.
 * 4. Facets across different groups or references are combined with logical AND.
 *
 * ## Example — combining generic and per-reference settings
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", attributeEquals("code", "e-readers")),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         facetSummary(COUNTS, entityFetch(attributeContent("name"))),
 *         facetSummaryOfReference(
 *             "parameterValues",
 *             IMPACT,
 *             filterBy(attributeContains("code", "memory")),
 *             filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
 *             orderBy(attributeNatural("name", ASC)),
 *             orderGroupBy(attributeNatural("name", ASC)),
 *             entityFetch(attributeContent("code", "name")),
 *             entityGroupFetch(attributeContent("code"))
 *         )
 *     )
 * )
 * ```
 *
 * In this example all faceted references use `COUNTS` with a basic name fetch, except `parameterValues` which uses
 * `IMPACT`, applies its own filters and ordering, and loads additional attributes.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
 *
 * @see FacetSummary
 * @see FacetStatisticsDepth
 * @see FacetCalculationRules
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary statistics for a single named reference with custom fetching, filtering, and ordering settings.",
	userDocsLink = "/documentation/query/requirements/facet#facet-summary-of-reference"
)
public class FacetSummaryOfReference
	extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummaryOfReference(
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, children, additionalChildren);
		Assert.notNull(
			getReferenceName(),
			"Facet summary requires reference name."
		);
		Assert.notNull(
			getStatisticsDepth(),
			"Facet summary requires a facet statistics depth specification."
		);
		for (RequireConstraint child : getChildren()) {
			Assert.isTrue(
				child instanceof EntityFetch ||
					child instanceof EntityGroupFetch,
				"Facet summary accepts only `EntityFetch` and `EntityGroupFetch` constraints."
			);
		}
		Assert.isTrue(
			Arrays.stream(getChildren()).filter(EntityFetch.class::isInstance).count() <= 1,
			"Facet summary accepts only one `EntityFetch` constraint."
		);
		Assert.isTrue(
			Arrays.stream(getChildren()).filter(EntityGroupFetch.class::isInstance).count() <= 1,
			"Facet summary accepts only one `EntityGroupFetch` constraint."
		);
		for (Constraint<?> child : additionalChildren) {
			Assert.isTrue(
				child instanceof FilterBy ||
					child instanceof FilterGroupBy ||
					child instanceof OrderBy ||
					child instanceof OrderGroupBy,
				"Facet summary accepts only `FilterBy`, `FilterGroupBy`, `OrderBy` and `OrderGroupBy` constraints."
			);
		}
	}

	public FacetSummaryOfReference(@Nonnull String referenceName) {
		super(new Serializable[]{referenceName, FacetStatisticsDepth.COUNTS});
	}

	public FacetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nonnull EntityFetchRequire... requirements
	) {
		this(
			new Serializable[]{referenceName, Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)},
			requirements
		);
	}

	@Creator
	public FacetSummaryOfReference(
		@Nonnull @Classifier String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) FilterBy filterBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.GROUP_ENTITY) FilterGroupBy filterGroupBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderBy orderBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.GROUP_ENTITY) OrderGroupBy orderGroupBy,
		@Nonnull @Child(uniqueChildren = true) EntityFetchRequire... requirements
	) {
		super(
			new Serializable[]{
				referenceName,
				Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)
			},
			requirements,
			new Constraint<?>[]{
				filterBy,
				filterGroupBy,
				orderBy,
				orderGroupBy
			}
		);
	}

	/**
	 * Name of reference to which this facet summary settings relates to.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * The mode controls whether FacetSummary should contain only basic statistics about facets - e.g. count only,
	 * or whether the selection impact should be computed as well.
	 */
	@Nonnull
	public FacetStatisticsDepth getStatisticsDepth() {
		return (FacetStatisticsDepth) getArguments()[1];
	}

	/**
	 * Returns content requirements for facet entities.
	 */
	@Nonnull
	public Optional<EntityFetch> getFacetEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(EntityFetch.class::isInstance)
			.map(EntityFetch.class::cast)
			.findFirst();
	}

	/**
	 * Returns content requirements for group entities.
	 */
	@Nonnull
	public Optional<EntityGroupFetch> getGroupEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(EntityGroupFetch.class::isInstance)
			.map(EntityGroupFetch.class::cast)
			.findFirst();
	}

	/**
	 * Returns filtering constraints for facets.
	 */
	@Nonnull
	public Optional<FilterBy> getFilterBy() {
		return getAdditionalChild(FilterBy.class);
	}

	/**
	 * Returns filtering constraints for facet groups.
	 */
	@Nonnull
	public Optional<FilterGroupBy> getFilterGroupBy() {
		return getAdditionalChild(FilterGroupBy.class);
	}

	/**
	 * Returns ordering constraints for facets.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return getAdditionalChild(OrderBy.class);
	}

	/**
	 * Returns ordering constraints for facet groups.
	 */
	@Nonnull
	public Optional<OrderGroupBy> getOrderGroupBy() {
		return getAdditionalChild(OrderGroupBy.class);
	}

	@AliasForParameter("requirements")
	@Nonnull
	@Override
	public RequireConstraint[] getChildren() {
		return super.getChildren();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != FacetStatisticsDepth.COUNTS)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == FacetStatisticsDepth.COUNTS;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new FacetSummaryOfReference(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetSummaryOfReference(
			newArguments,
			getChildren(),
			getAdditionalChildren()
		);
	}
}
