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
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The node filtering container is an alternative to the {@link HierarchyDistance} and {@link HierarchyLevel}
 * termination constraints, which is much more dynamic and can produce hierarchy trees of non-uniform depth. Because
 * the filtering constraint can be satisfied by nodes of widely varying depths, traversal can be highly dynamic.
 *
 * Constraint children define a criterion that determines the point in a hierarchical structure where the traversal
 * should stop. The traversal stops at the first node that satisfies the filter condition specified in this container.
 *
 * The situations where you'd need this dynamic behavior are few and far between. Unfortunately, we do not have
 * a meaningful example of this in the demo dataset, so our example query will be slightly off. But for the sake of
 * demonstration, let's list the entire Accessories hierarchy, but stop traversing at the nodes whose code starts with
 * the letter `w`.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             children(
 *                 "subMenu",
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(
 *                     node(
 *                         filterBy(
 *                             attributeStartsWith("code", "w")
 *                         )
 *                     )
 *                 )
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#node">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "node",
	shortDescription = "The constraint allows to locate the pivot hierarchy node.",
	userDocsLink = "/documentation/query/requirements/hierarchy#node",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyNode extends AbstractRequireConstraintContainer implements HierarchyStopAtRequireConstraint {
	@Serial private static final long serialVersionUID = -7033476265993356981L;
	private static final String CONSTRAINT_NAME = "node";

	@Creator
	public HierarchyNode(@Nonnull @AdditionalChild(domain = ConstraintDomain.ENTITY) FilterBy filterBy) {
		super(CONSTRAINT_NAME, new Serializable[0], new RequireConstraint[0], filterBy);
	}

	/**
	 * Contains filtering condition that identifies the pivot node. The filtering constraint must match exactly one
	 * pivot node.
	 */
	@Nonnull
	public FilterBy getFilterBy() {
		return getAdditionalChild(FilterBy.class)
			.orElseThrow(() -> new IllegalStateException("Hierarchy node expects FilterBy as its single inner constraint!"));
	}

	@Override
	public boolean isApplicable() {
		return getAdditionalChildrenCount() == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(children), "Inner constraints of different type than FilterBy are not expected.");
		Assert.isTrue(additionalChildren.length == 1, "HierarchyNode expect FilterBy inner constraint!");
		for (Constraint<?> constraint : additionalChildren) {
			Assert.isTrue(
				constraint instanceof FilterBy,
				"Constraint HierarchyNode accepts only FilterBy as inner constraint!"
			);
		}

		return new HierarchyNode((FilterBy) additionalChildren[0]);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "HierarchyNode container accepts no arguments!");
		return this;
	}

}
