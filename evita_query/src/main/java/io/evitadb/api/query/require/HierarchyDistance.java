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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Specifies the maximum relative distance from the pivot node at which hierarchy traversal stops. The distance
 * is always measured in edge hops from the pivot — the node where traversal starts — and applies regardless of
 * the direction of traversal (top-down or bottom-up):
 *
 * - Distance 0: the pivot node itself
 * - Distance 1: the direct children (or parent) of the pivot node
 * - Distance 2: grandchildren (or grandparent) of the pivot node
 * - Distance N: nodes N hops away from the pivot
 *
 * The distance between any two nodes in the hierarchy can be computed as `abs(level(nodeA) - level(nodeB))`.
 * The distance value must be greater than zero (a distance of zero would include only the pivot node itself,
 * which is always included automatically).
 *
 * This constraint can only be used as the single inner constraint of a {@link HierarchyStopAt} container.
 * It is the right choice when you need a traversal depth that is _relative_ to wherever the pivot sits in the
 * tree — for example, "always show me one level below the currently focused node (direct children only)" —
 * regardless of its absolute depth.
 *
 * Contrast with {@link HierarchyLevel}, which specifies an _absolute_ level from the root, and
 * {@link HierarchyNode}, which stops dynamically based on a filter condition.
 *
 * **Example — flat direct-children list (distance 1) of the focused "Audio" category:**
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
 *                 stopAt(distance(1))
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#distance)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "distance",
	shortDescription = "The constraint limits hierarchy traversal to a maximum distance (number of edge hops) from the pivot node.",
	userDocsLink = "/documentation/query/requirements/hierarchy#distance",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyDistance extends AbstractRequireConstraintLeaf implements HierarchyStopAtRequireConstraint {
	@Serial private static final long serialVersionUID = 2412732472259053834L;
	private static final String CONSTRAINT_NAME = "distance";

	private HierarchyDistance(Serializable... arguments) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyDistance(int distance) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, distance);
		Assert.isTrue(distance > 0, () -> new EvitaInvalidUsageException("Distance must be greater than zero."));
	}

	/**
	 * Returns distance from the source node that should be traversed.
	 */
	public int getDistance() {
		return (Integer) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Integer,
			"HierarchyDistance container accepts only single integer argument!"
		);
		return new HierarchyDistance(newArguments);
	}

}
