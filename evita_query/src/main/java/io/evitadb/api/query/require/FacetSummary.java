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
 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
 * summary can be further modified by the facet summary of reference constraint, which allows you to override
 * the general facet summary behavior specified in the generic facet summary require constraint.
 *
 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
 * references for the summary.
 *
 * ## Facet calculation rules
 *
 * 1. The facet summary is calculated only for entities that are returned in the current query result.
 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
 * 3. The default relation between facets within a group is logical disjunction (logical OR).
 * 4. The default relation between facets in different groups / references is a logical AND.
 *
 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
 * queried entities.
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
 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary of all facet in searched scope into response with default \"fetching\" settings for all referenced entities.",
	userDocsLink = "/documentation/query/requirements/facet#facet-summary"
)
public class FacetSummary extends AbstractRequireConstraintContainer
	implements FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
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
		super(new Serializable[]{FacetStatisticsDepth.COUNTS}, new EntityContentRequire[0]);
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth) {
		super(new Serializable[]{statisticsDepth});
	}

	@Creator
	public FacetSummary(
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nonnull @Child(uniqueChildren = true) EntityRequire... requirements) {
		this(new Serializable[]{statisticsDepth}, requirements);
	}

	public FacetSummary(
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy filterGroupBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy orderGroupBy,
		@Nonnull EntityRequire... requirements
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