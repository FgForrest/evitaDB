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
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
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
 * The `facetSummary` requirement triggers the calculation of the
 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} extra result, which contains facet statistics for
 * all entity references that are marked as **faceted** in the entity schema. The facet summary is computed as a side
 * effect of the main entity query and always reflects the same filtering scope — it only counts entities that would
 * actually be returned by the current query.
 *
 * ## Result structure
 *
 * The computed extra result is organized into three tiers:
 *
 * - **References** — one entry per faceted reference name (e.g. `brand`, `parameterValues`)
 * - **Facet groups** — groupings of related facet options within a reference (e.g. `Color`, `Size`)
 * - **Facets** — individual selectable options, each with a count of matching entities, a flag indicating whether it
 *   is already selected, and optionally an impact prediction
 *
 * ## Statistics depth
 *
 * The first argument selects how much data is computed for each facet option:
 *
 * - `COUNTS` *(default)* — only the count of entities matching that facet in the current result is calculated; this
 *   is cheaper and sufficient for basic "how many results does this option have?" UIs
 * - `IMPACT` — additionally computes a prediction of how many results would be returned if the user selected that
 *   facet option right now; this is more expensive but enables "X results remaining" style UI feedback
 *
 * `COUNTS` is the implicit default and is omitted from the EvitaQL string representation.
 *
 * ## Default facet calculation rules
 *
 * Unless overridden by {@link FacetCalculationRules} or the per-group behavior constraints
 * ({@link FacetGroupsConjunction}, {@link FacetGroupsDisjunction}, {@link FacetGroupsNegation},
 * {@link FacetGroupsExclusivity}), the following rules apply:
 *
 * 1. The facet summary covers only entities returned in the current query result.
 * 2. Filter constraints placed **outside** `userFilter` are always respected and cannot be overridden by facet
 *    selection.
 * 3. Facets **within the same group** are combined with logical OR (disjunction) — selecting blue OR red.
 * 4. Facets **across different groups or references** are combined with logical AND (conjunction) — must be blue AND
 *    large.
 *
 * ## Entity fetch requirements
 *
 * You can attach at most one {@link EntityFetch} and at most one {@link EntityGroupFetch} child constraint to control
 * which data is loaded for the facet entities and their group entities respectively in the result. Passing two
 * constraints of the same type throws an exception at construction time.
 *
 * ## Filtering the facet summary
 *
 * When the summary is too large to be practical, you can supply a {@link FilterBy} constraint (targeting individual
 * facet entities) and/or a {@link FilterGroupBy} constraint (targeting entire facet groups) as additional children.
 * These filters only apply to which facet options appear in the summary — they do not affect which entities are
 * counted. Because these filters are applied uniformly across all faceted references, they can only reference
 * properties shared by all referenced entity types. When you need reference-specific filtering, use
 * {@link FacetSummaryOfReference} instead.
 *
 * ## Ordering the facet summary
 *
 * Similarly, you can attach {@link OrderBy} (for individual facets) and/or {@link OrderGroupBy} (for groups) to
 * control the sort order of options and groups in the result. The same cross-reference restriction applies: you can
 * only sort by properties shared across all referenced entity types.
 *
 * ## Interaction with FacetSummaryOfReference
 *
 * A generic `facetSummary` can coexist in the same `require()` container with one or more
 * {@link FacetSummaryOfReference} constraints. When both are present, `facetSummaryOfReference` **completely overrides**
 * all constraints from the generic `facetSummary` for that particular reference — the constraints are not merged. This
 * pattern lets you define common defaults once and specialize only the references that need different behaviour.
 *
 * ## Performance note
 *
 * Marking a reference as faceted enlarges the in-memory indexes maintained by the engine. The combinatorial complexity
 * of the facet summary calculation grows with the number of faceted references, groups, and options. For large
 * datasets it is strongly recommended to keep only the references that are actually used for faceted navigation as
 * faceted, and to limit the summary using filters where appropriate.
 *
 * ## Example
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", attributeEquals("code", "e-readers")),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         facetSummary(
 *             IMPACT,
 *             filterBy(attributeStartsWith("code", "a")),
 *             filterGroupBy(attributeEquals("visible", true)),
 *             orderBy(attributeNatural("name", ASC)),
 *             orderGroupBy(attributeNatural("name", ASC)),
 *             entityFetch(attributeContent("name")),
 *             entityGroupFetch(attributeContent("name"))
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
 *
 * @see FacetSummaryOfReference
 * @see FacetCalculationRules
 * @see FacetGroupsConjunction
 * @see FacetGroupsDisjunction
 * @see FacetGroupsNegation
 * @see FacetGroupsExclusivity
 * @see FacetStatisticsDepth
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary statistics for all faceted references in the query scope with shared fetching settings.",
	userDocsLink = "/documentation/query/requirements/facet#facet-summary"
)
public class FacetSummary extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummary(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
		Assert.notNull(
			getStatisticsDepth(),
			"Facet summary requires a facet statistics depth specification."
		);
		for (RequireConstraint child : children) {
			Assert.isTrue(
				child instanceof EntityFetch ||
					child instanceof EntityGroupFetch,
				"Facet summary accepts only `EntityFetch` and `EntityGroupFetch` constraints."
			);
		}
		Assert.isTrue(
			Arrays.stream(children).filter(EntityFetch.class::isInstance).count() <= 1,
			"Facet summary accepts only one `EntityFetch` constraint."
		);
		Assert.isTrue(
			Arrays.stream(children).filter(EntityGroupFetch.class::isInstance).count() <= 1,
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

	public FacetSummary() {
		super(new Serializable[]{FacetStatisticsDepth.COUNTS}, EntityContentRequire.EMPTY_ARRAY);
	}

	public FacetSummary(@Nullable FacetStatisticsDepth statisticsDepth) {
		super(new Serializable[]{Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)});
	}

	@Creator
	public FacetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nonnull @Child(uniqueChildren = true) EntityFetchRequire... requirements
	) {
		this(
			new Serializable[]{Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)},
			requirements
		);
	}

	public FacetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy filterGroupBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy orderGroupBy,
		@Nonnull EntityFetchRequire... requirements
	) {
		super(
			new Serializable[]{Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)},
			requirements,
			filterBy,
			filterGroupBy,
			orderBy,
			orderGroupBy
		);
		Assert.isTrue(
			requirements.length <= 2,
			"Expected maximum number of 2 entity requirements. Found " + requirements.length + "."
		);
		if (requirements.length == 2) {
			Assert.isTrue(
				!requirements[0].getClass().equals(requirements[1].getClass()),
				"Cannot have two same entity requirements."
			);
		}
	}

	/**
	 * The mode controls whether FacetSummary should contain only basic statistics about facets - e.g. count only,
	 * or whether the selection impact should be computed as well.
	 */
	@Nonnull
	public FacetStatisticsDepth getStatisticsDepth() {
		return (FacetStatisticsDepth) getArguments()[0];
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
		return Arrays.stream(getAdditionalChildren())
			.filter(FilterBy.class::isInstance)
			.map(FilterBy.class::cast)
			.findFirst();
	}

	/**
	 * Returns filtering constraints for facet groups.
	 */
	@Nonnull
	public Optional<FilterGroupBy> getFilterGroupBy() {
		return Arrays.stream(getAdditionalChildren())
			.filter(FilterGroupBy.class::isInstance)
			.map(FilterGroupBy.class::cast)
			.findFirst();
	}

	/**
	 * Returns ordering constraints for facets.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return Arrays.stream(getAdditionalChildren())
			.filter(OrderBy.class::isInstance)
			.map(OrderBy.class::cast)
			.findFirst();
	}

	/**
	 * Returns ordering constraints for facet groups.
	 */
	@Nonnull
	public Optional<OrderGroupBy> getOrderGroupBy() {
		return Arrays.stream(getAdditionalChildren())
			.filter(OrderGroupBy.class::isInstance)
			.map(OrderGroupBy.class::cast)
			.findFirst();
	}

	@AliasForParameter("requirements")
	@Nonnull
	@Override
	public RequireConstraint[] getChildren() {
		return super.getChildren();
	}

	@Override
	public boolean isApplicable() {
		return true;
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
		return new FacetSummary(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetSummary(
			newArguments,
			getChildren(),
			getAdditionalChildren()
		);
	}
}
