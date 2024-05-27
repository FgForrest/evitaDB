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
 * The distance constraint can only be used within the {@link HierarchyStopAt} container and limits the hierarchy
 * traversal to stop when the number of levels traversed reaches the specified constant. The distance is always relative
 * to the pivot node (the node where the hierarchy traversal starts) and is the same whether we are traversing
 * the hierarchy top-down or bottom-up. The distance between any two nodes in the hierarchy can be calculated as
 * `abs(level(nodeA) - level(nodeB))`.
 *
 * The constraint accepts single integer argument `distance`, which defines a maximum relative distance from the pivot
 * node that can be traversed; the pivot node itself is at distance zero, its direct child or direct parent is
 * at distance one, each additional step adds a one to the distance.
 *
 * See the following figure when the pivot node is Audio:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "audio")
 *         )
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
 * </pre>
 *
 * The following query lists products in category Audio and its subcategories. Along with the products returned, it
 * also returns a computed subcategories data structure that lists the flat category list the currently focused category
 * Audio.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#distance">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "distance",
	shortDescription = "The constraint limits the traversing in stop at container at specified distance (number of nodes in path).",
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
