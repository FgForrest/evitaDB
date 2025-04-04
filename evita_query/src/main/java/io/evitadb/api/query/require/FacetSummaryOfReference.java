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
 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
 * the default constraints from the generic requirement to constraints specific to this particular reference.
 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
 * summary calculation, and redefine them only for references where they are insufficient.
 *
 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
 *
 * ## Facet calculation rules
 *
 * 1. The facet summary is calculated only for entities that are returned in the current query result.
 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
 * 3. The default relation between facets within a group is logical disjunction (logical OR).
 * 4. The default relation between facets in different groups / references is a logical AND.
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "e-readers")
 *         )
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         facetSummary(
 *             COUNTS,
 *             entityFetch(
 *                 attributeContent("name")
 *             ),
 *             entityGroupFetch(
 *                 attributeContent("name")
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * ## Filtering facet summary
 *
 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
 * instead of individual facets) constraints.
 *
 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
 * specific filters for each reference type.
 *
 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
 * the source entity that are specific to a relationship with the target entity.
 *
 * ## Ordering facet summary
 *
 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
 * constraints.
 *
 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
 * constraints for each reference type.
 *
 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
 * the source entity that are specific to a relationship with the target entity.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary of all facet in searched scope into response with custom \"fetching\" settings for specific reference.",
	userDocsLink = "/documentation/query/requirements/facet#facet-summary-of-reference"
)
public class FacetSummaryOfReference
	extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummaryOfReference(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
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
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
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
