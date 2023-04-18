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
import io.evitadb.api.query.descriptor.annotation.Child;
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
	shortDescription = "The constraint triggers computation of facet summary of all facet in searched scope into response with default \"fetching\" settings for all referenced entities."
)
public class FacetSummary extends AbstractRequireConstraintContainer implements FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummary(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
		Assert.notNull(
			getFacetStatisticsDepth(),
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
		super(new Serializable[]{FacetStatisticsDepth.COUNTS}, new EntityContentRequire[0]);
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth) {
		super(new Serializable[]{statisticsDepth});
	}

	public FacetSummary(
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nonnull EntityRequire... requirements) {
		this(new Serializable[]{statisticsDepth}, requirements);
	}

	@Creator
	public FacetSummary(
		@Nonnull @Value FacetStatisticsDepth statisticsDepth,
		@Nonnull @Child FilterBy filterBy,
		@Nonnull @Child FilterGroupBy filterGroupBy,
		@Nonnull @Child OrderBy orderBy,
		@Nonnull @Child OrderGroupBy orderGroupBy,
		@Nonnull @Child(uniqueChildren = true) EntityRequire... requirements
	) {
		super(
			new Serializable[]{statisticsDepth},
			requirements,
			new Constraint<?>[]{
				filterBy,
				filterGroupBy,
				orderBy,
				orderGroupBy
			}
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
	public FacetStatisticsDepth getFacetStatisticsDepth() {
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

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
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
