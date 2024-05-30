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
 * The level constraint can only be used within the stopAt container and limits the hierarchy traversal to stop when
 * the actual level of the traversed node is equal to a specified constant. The "virtual" top invisible node has level
 * zero, the top nodes (nodes with NULL parent) have level one, their children have level two, and so on.
 *
 * See the following figure:
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
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(level(2))
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * The query lists products in Audio category and its subcategories. Along with the products returned, it
 * also returns a computed megaMenu data structure that lists top two levels of the entire hierarchy.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#level">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "level",
	shortDescription = "The constraint limits the traversing in stop at container at specified level from root.",
	userDocsLink = "/documentation/query/requirements/hierarchy#level",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyLevel extends AbstractRequireConstraintLeaf implements HierarchyStopAtRequireConstraint {
	@Serial private static final long serialVersionUID = 6617175711838249198L;
	private static final String CONSTRAINT_NAME = "level";

	private HierarchyLevel(Serializable... arguments) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyLevel(int level) {
		super(CONSTRAINT_NAME, level);
		Assert.isTrue(level > 0, () -> new EvitaInvalidUsageException("Level must be greater than zero. Level 1 represents root node."));

	}

	/**
	 * Returns the final level that should be traversed, no other levels will be traversed any more.
	 */
	public int getLevel() {
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
			"HierarchyLevel container accepts only single integer argument!"
		);
		return new HierarchyLevel(newArguments);
	}

}
