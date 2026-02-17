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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.HierarchyAnyHaving;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Computes a hierarchy tree starting from the "virtual" invisible top root of the hierarchy, regardless of any
 * {@link HierarchyWithin} filter constraint used in the same query. This makes it ideal for rendering complete
 * navigation structures such as mega-menus, even when the query itself is filtered to a specific subtree.
 *
 * The traversal goes all the way to the leaf nodes unless limited by a {@link HierarchyStopAt} inner constraint.
 * Statistical counts (child node counts, queried entity counts) can be added via a {@link HierarchyStatistics}
 * inner constraint.
 *
 * **Interaction with `hierarchyWithin` filter:**
 *
 * The pivot node specified in `hierarchyWithin` does not affect which nodes `fromRoot` starts from — it always
 * starts from the top. However, the `having`, `anyHaving`, and `excluding` inner constraints of `hierarchyWithin`
 * _are_ respected during statistics computation, ensuring that entity counts stay consistent with what the user
 * would see when navigating to a given node.
 *
 * **Performance note:** Computing {@link StatisticsType#QUERIED_ENTITY_COUNT} for `fromRoot` on large datasets is
 * expensive because it requires aggregation across the entire indexed dataset. Consider using
 * {@link StatisticsType#CHILDREN_COUNT} alone, or applying a tight {@link HierarchyStopAt} to limit the traversal
 * depth when full statistics are not required. Caching is recommended for production use.
 *
 * **Required arguments:**
 *
 * - mandatory `outputName` (`String`): key under which the computed result is registered in the extra results map
 *
 * **Optional inner constraints:**
 *
 * - {@link EntityFetch}: specifies which data to fetch for each hierarchy entity in the result
 * - {@link HierarchyStopAt}: limits how deep the traversal descends
 * - {@link HierarchyStatistics}: requests computation of `CHILDREN_COUNT` and/or `QUERIED_ENTITY_COUNT` per node
 *
 * **Example — full top-2-level mega-menu while filtering products in the Audio category:**
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", attributeEquals("code", "audio"))
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(level(2)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-root)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "fromRoot",
	shortDescription = "The constraint computes a full hierarchy tree starting from the invisible top root, regardless of any hierarchyWithin filter.",
	userDocsLink = "/documentation/query/requirements/hierarchy#from-root",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyFromRoot extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 8754876413427697151L;
	private static final String CONSTRAINT_NAME = "fromRoot";

	public HierarchyFromRoot(
		@Nonnull String outputName,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, children, additionalChildren);
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyOutputRequireConstraint ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchyFromRoot accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
			);
		}
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyFromRoot accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
		);
	}

	@Creator
	public HierarchyFromRoot(@Nonnull String outputName,
	                         @Nullable EntityFetch entityFetch,
	                         @Nonnull @Child(uniqueChildren = true) HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[]{entityFetch},
				requirements
			)
		);
	}

	public HierarchyFromRoot(@Nonnull String outputName, @Nonnull HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			requirements
		);
	}

	public HierarchyFromRoot(@Nonnull String outputName) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName});
	}

	/**
	 * Returns the key the computed extra result should be registered to.
	 */
	@Nonnull
	@Override
	public String getOutputName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the condition that limits the top-down hierarchy traversal.
	 */
	@Nonnull
	public Optional<HierarchyStopAt> getStopAt() {
		for (RequireConstraint argument : getChildren()) {
			if (argument instanceof HierarchyStopAt hierarchyStopAt) {
				return of(hierarchyStopAt);
			}
		}
		return empty();
	}

	/**
	 * Returns content requirements for hierarchy entities.
	 */
	@Nonnull
	public Optional<EntityFetch> getEntityFetch() {
		for (RequireConstraint argument : getChildren()) {
			if (argument instanceof EntityFetch entityFetch) {
				return of(entityFetch);
			}
		}
		return empty();
	}

	/**
	 * Returns {@link HierarchyStatistics} settings.
	 */
	@Nonnull
	public Optional<HierarchyStatistics> getStatistics() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStatistics statistics) {
				return of(statistics);
			}
		}
		return empty();
	}

	@AliasForParameter("requirements")
	@Nonnull
	public HierarchyOutputRequireConstraint[] getOutputRequirements() {
		return Arrays.stream(getChildren())
			.filter(it -> HierarchyOutputRequireConstraint.class.isAssignableFrom(it.getClass()))
			.map(HierarchyOutputRequireConstraint.class::cast)
			.toArray(HierarchyOutputRequireConstraint[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyFromRoot(getOutputName(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"HierarchyFromRoot container accepts only single String argument!"
		);
		return new HierarchyFromRoot((String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
