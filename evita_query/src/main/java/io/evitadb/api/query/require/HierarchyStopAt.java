/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * A wrapper container that defines the termination condition for hierarchy traversal. It is used as an inner
 * constraint inside any of the traversal requirements ({@link HierarchyFromRoot}, {@link HierarchyFromNode},
 * {@link HierarchyChildren}, {@link HierarchyParents}, {@link HierarchySiblings}) to prevent unbounded descent
 * through arbitrarily deep trees.
 *
 * Without a `stopAt`, traversal continues all the way to the leaf nodes (for top-down traversal) or to the root
 * (for upward traversal such as `parents`), which may be expensive for large hierarchies.
 *
 * The container must contain exactly one of the following termination strategies:
 *
 * - {@link HierarchyDistance}: stops when the traversal has moved the specified number of steps away from the pivot
 *   node (relative depth); the pivot node itself is at distance zero
 * - {@link HierarchyLevel}: stops when the traversal reaches nodes at or beyond the specified absolute level in
 *   the hierarchy; the virtual root is level zero, top-level nodes are level one
 * - {@link HierarchyNode}: stops at the first node whose attributes or other properties satisfy the specified
 *   filter constraint; allows non-uniform tree depth based on data-driven conditions
 *
 * **Example — stop at depth 2 from the root when building a mega-menu:**
 *
 * ```evitaql
 * hierarchyOfReference(
 *     "categories",
 *     fromRoot(
 *         "megaMenu",
 *         stopAt(level(2))
 *     )
 * )
 * ```
 *
 * **Example — stop one level below the pivot node (direct children only):**
 *
 * ```evitaql
 * hierarchyOfReference(
 *     "categories",
 *     children(
 *         "subcategories",
 *         stopAt(distance(1))
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#stop-at)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "stopAt",
	shortDescription = "The constraint defines the traversal stop condition that limits the scope of the returned hierarchy tree.",
	userDocsLink = "/documentation/query/requirements/hierarchy#stop-at",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyStopAt extends AbstractRequireConstraintContainer implements HierarchyOutputRequireConstraint {
	@Serial private static final long serialVersionUID = -8571440669425103884L;
	private static final String CONSTRAINT_NAME = "stopAt";

	private HierarchyStopAt(@Nonnull RequireConstraint[] children) {
		super(CONSTRAINT_NAME, children);
	}

	@Creator
	public HierarchyStopAt(@Nonnull @Child(uniqueChildren = true) HierarchyStopAtRequireConstraint stopAtDefinition) {
		super(CONSTRAINT_NAME, stopAtDefinition);
	}

	@Nonnull
	public HierarchyStopAtRequireConstraint getStopAtDefinition() {
		return (HierarchyStopAtRequireConstraint) getChildren()[0];
	}

	/**
	 * Returns constraint that limits the hierarchy traversal by absolute depth from the root.
	 */
	@Nullable
	public HierarchyLevel getLevel() {
		for (RequireConstraint argument : getChildren()) {
			if (argument instanceof HierarchyLevel hierarchyLevel) {
				return hierarchyLevel;
			}
		}
		return null;
	}

	/**
	 * Returns constraint that limits the hierarchy traversal by relative distance from queried hierarchy node.
	 */
	@Nullable
	public HierarchyDistance getDistance() {
		for (RequireConstraint argument : getChildren()) {
			if (argument instanceof HierarchyDistance hierarchyDistance) {
				return hierarchyDistance;
			}
		}
		return null;
	}

	/**
	 * Returns a constraint that limits the traversal of the hierarchy by reaching the nodes that satisfy the filter
	 * condition.
	 */
	@Nullable
	public HierarchyNode getNode() {
		for (RequireConstraint argument : getChildren()) {
			if (argument instanceof HierarchyNode hierarchyNode) {
				return hierarchyNode;
			}
		}
		return null;
	}

	@Override
	public boolean isApplicable() {
		return getChildrenCount() >= 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyStopAtRequireConstraint ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchyChildren accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
			);
		}

		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "Inner constraints of different type than `require` are not expected.");
		return new HierarchyStopAt(children);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "HierarchyStopAt container accepts no arguments!");
		return new HierarchyStopAt(getChildren());
	}

}
