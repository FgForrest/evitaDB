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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
 *
 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
 * container constraint.
 *
 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} is stored to result.
 *
 * Optionally accepts single enum argument:
 *
 * - COUNT: only counts of facets will be computed
 * - IMPACT: counts and selection impact for non-selected facets will be computed
 *
 * Example:
 *
 * ```
 * facetSummary()
 * facetSummary(COUNT) //same as previous row - default
 * facetSummary(IMPACT)
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary of all facet in searched scope into response with custom \"fetching\" settings for specific reference."
)
public class FacetSummaryOfReference extends AbstractRequireConstraintContainer implements FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummaryOfReference(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
		Assert.notNull(
			getReferenceName(),
			"Facet summary requires reference name."
		);
		Assert.notNull(
			getFacetStatisticsDepth(),
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

	// todo lho přepsat na entityFetch a groupFetch?
	public FacetSummaryOfReference(
		@Nonnull String referenceName,
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nonnull EntityRequire... requirements
	) {
		this(new Serializable[]{referenceName, statisticsDepth}, requirements);
	}

	@Creator
	public FacetSummaryOfReference(
		@Nonnull @Classifier String referenceName,
		@Nonnull @Value FacetStatisticsDepth statisticsDepth,
		@Nonnull @AdditionalChild(domain = ConstraintDomain.ENTITY) FilterBy filterBy,
		@Nonnull @AdditionalChild(domain = ConstraintDomain.ENTITY) FilterGroupBy filterGroupBy,
		@Nonnull @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderBy orderBy,
		@Nonnull @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderGroupBy orderGroupBy,
		@Nonnull @Child(uniqueChildren = true) EntityRequire... requirements
	) {
		super(
			new Serializable[]{referenceName, Optional.ofNullable(statisticsDepth).orElse(FacetStatisticsDepth.COUNTS)},
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
	public FacetStatisticsDepth getFacetStatisticsDepth() {
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

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
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
