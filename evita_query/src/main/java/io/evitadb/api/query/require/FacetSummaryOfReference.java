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
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;
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
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "summary",
	shortDescription = "The constraint triggers computation of facet summary of all facet in searched scope into response with custom \"fetching\" settings for specific reference."
)
public class FacetSummaryOfReference extends AbstractRequireConstraintContainer implements FacetConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = 2377379601711709241L;

	private FacetSummaryOfReference(Serializable... arguments) {
		super(arguments);
	}

	public FacetSummaryOfReference(@Nonnull String referenceName) {
		super(new Serializable[] {referenceName, FacetStatisticsDepth.COUNTS});
	}

	public FacetSummaryOfReference(@Nonnull String referenceName, @Nonnull @ConstraintValueParamDef FacetStatisticsDepth statisticsDepth) {
		super(new Serializable[] {referenceName, statisticsDepth});
	}

	public FacetSummaryOfReference(@Nonnull String referenceName,
	                               @Nonnull FacetStatisticsDepth statisticsDepth,
	                               @Nonnull EntityFetch entityRequirement) {
		super(new Serializable[] {referenceName, statisticsDepth}, entityRequirement, null);
	}

	public FacetSummaryOfReference(@Nonnull String referenceName,
	                               @Nonnull FacetStatisticsDepth statisticsDepth,
	                               @Nonnull EntityGroupFetch groupRequirement) {
		super(new Serializable[] {referenceName, statisticsDepth}, (EntityRequire) null, groupRequirement);
	}

	public FacetSummaryOfReference(@Nonnull String referenceName,
	                               @Nonnull FacetStatisticsDepth statisticsDepth,
	                               @Nullable EntityFetch facetEntityRequirement,
	                               @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new Serializable[] {referenceName, statisticsDepth}, facetEntityRequirement, groupEntityRequirement);
	}

	@ConstraintCreatorDef
	public FacetSummaryOfReference(@Nonnull @ConstraintClassifierParamDef String referenceName,
	                               @Nonnull @ConstraintValueParamDef FacetStatisticsDepth statisticsDepth,
	                               @Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) EntityRequire... requirements) {
		super(new Serializable[] {referenceName, statisticsDepth}, requirements);
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
		return new FacetSummaryOfReference(getArguments(), requireChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetSummaryOfReference(
			newArguments,
			getChildren()
		);
	}
}
