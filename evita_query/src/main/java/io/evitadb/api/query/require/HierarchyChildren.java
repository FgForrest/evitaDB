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
import io.evitadb.api.query.filter.HierarchyWithinRoot;
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
 * Computes the hierarchy subtree rooted at the same node that is targeted by the `hierarchyWithin` (or
 * `hierarchyWithinRoot`) filter constraint in the same query. The traversal then proceeds downward through
 * children, grandchildren, and so on. This constraint is the natural choice for rendering the sub-navigation
 * below the currently browsed category: the user is already scoped to "Audio", and you want to show them
 * "Headphones", "Speakers", "True Wireless", etc.
 *
 * By default the traversal goes all the way to the leaf nodes. Use a {@link HierarchyStopAt} inner constraint —
 * typically `stopAt(distance(1))` — to limit the result to only direct children (a flat list).
 *
 * **Interaction with `hierarchyWithin` filter:**
 *
 * The pivot node of `hierarchyWithin` is the starting point of the child traversal. The `having`, `anyHaving`,
 * and `excluding` inner constraints of `hierarchyWithin` are also respected during statistics computation, keeping
 * counts consistent with what the user would see when drilling into a sub-node.
 *
 * When used alongside `hierarchyWithinRoot`, the traversal starts from the virtual top root of the hierarchy,
 * making it equivalent to computing the full top-level category list.
 *
 * **Required arguments:**
 *
 * - mandatory `outputName` (`String`): key under which the computed subtree is registered in the extra results map
 *
 * **Optional inner constraints:**
 *
 * - {@link EntityFetch}: specifies which data to fetch for each hierarchy entity in the result
 * - {@link HierarchyStopAt}: limits how deep the downward traversal proceeds from the pivot node
 * - {@link HierarchyStatistics}: requests computation of `CHILDREN_COUNT` and/or `QUERIED_ENTITY_COUNT` per node
 *
 * **Example — direct subcategories of the filtered category with statistics:**
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
 *             children(
 *                 "subcategories",
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(distance(1)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#children)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "children",
	shortDescription = "The constraint computes a hierarchy subtree starting at the node targeted by the hierarchyWithin filter constraint, traversing downward through children.",
	userDocsLink = "/documentation/query/requirements/hierarchy#children",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyChildren extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 9160175156714580097L;
	private static final String CONSTRAINT_NAME = "children";

	private HierarchyChildren(
		@Nonnull String outputName,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, children, additionalChildren);
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyOutputRequireConstraint ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchyChildren accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
			);
		}
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyChildren accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
		);
	}

	@Creator
	public HierarchyChildren(@Nonnull String outputName,
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

	public HierarchyChildren(@Nonnull String outputName, @Nonnull HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			requirements
		);
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
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStopAt hierarchyStopAt) {
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
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof EntityFetch entityFetch) {
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
		return new HierarchyChildren(getOutputName(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"HierarchyChildren container accepts only single String argument!"
		);
		return new HierarchyChildren((String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
