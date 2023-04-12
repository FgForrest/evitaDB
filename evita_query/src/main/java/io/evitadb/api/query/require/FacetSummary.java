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
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

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

	private FacetSummary(Serializable... arguments) {
		super(arguments);
	}

	public FacetSummary() {
		super(new Serializable[] {FacetStatisticsDepth.COUNTS}, new EntityContentRequire[0]);
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth) {
		super(new Serializable[] {statisticsDepth});
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth,
	                    @Nonnull EntityFetch entityRequirement) {
		super(new Serializable[] {statisticsDepth}, entityRequirement);
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth,
	                    @Nonnull EntityGroupFetch groupRequirement) {
		super(new Serializable[] {statisticsDepth}, groupRequirement);
	}

	public FacetSummary(@Nonnull FacetStatisticsDepth statisticsDepth,
	                    @Nullable EntityFetch facetEntityRequirement,
	                    @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new Serializable[] {statisticsDepth}, facetEntityRequirement, groupEntityRequirement);
	}

	@Creator
	public FacetSummary(@Nonnull @Value FacetStatisticsDepth statisticsDepth,
	                    @Nonnull @Child(uniqueChildren = true) EntityRequire... requirements) {
		super(new Serializable[] {statisticsDepth}, requirements);
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
	 * Returns requirements for facet entities.
	 */
	@Nullable
	public EntityFetch getFacetEntityRequirement() {
		final int childrenLength = getChildren().length;
		if (childrenLength == 2) {
			return (EntityFetch) getChildren()[0];
		} else if (childrenLength == 1) {
			if (getChildren()[0] instanceof final EntityFetch facetEntityRequirement) {
				return facetEntityRequirement;
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	/**
	 * Returns requirements for group entities.
	 */
	@Nullable
	public EntityGroupFetch getGroupEntityRequirement() {
		final int childrenLength = getChildren().length;
		if (childrenLength == 2) {
			return (EntityGroupFetch) getChildren()[1];
		} else if (childrenLength == 1) {
			if (getChildren()[0] instanceof final EntityGroupFetch groupEntityRequirement) {
				return groupEntityRequirement;
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull Constraint<?>[] children, @Nonnull Constraint<?>[] additionalChildren) {
		final RequireConstraint[] requireChildren = Arrays.stream(children)
			.map(c -> (RequireConstraint) c)
			.toArray(RequireConstraint[]::new);
		return new FacetSummary(getArguments(), requireChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetSummary(
			newArguments,
			getChildren()
		);
	}
}
