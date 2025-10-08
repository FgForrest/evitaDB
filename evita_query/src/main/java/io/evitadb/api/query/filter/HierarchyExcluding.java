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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint `excluding` is a constraint that can only be used within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
 * that are transitively or directly related to them, and the parent node itself.
 *
 * The excluding constraint allows you to exclude one or more subtrees from the scope of the filter. This constraint is
 * the exact opposite of the having constraint. If the constraint is true for a hierarchy entity, it and all of its
 * children are excluded from the query. The excluding constraint is the same as declaring `having(not(expression))`,
 * but for the sake of readability it has its own constraint.
 *
 * The constraint accepts following arguments:
 *
 * - one or more mandatory constraints that must be satisfied by all returned hierarchy nodes and that mark the visible
 *   part of the tree, the implicit relation between constraints is logical conjunction (boolean AND)
 *
 * When the hierarchy constraint targets the hierarchy entity, the children that satisfy the inner constraints (and
 * their children, whether they satisfy them or not) are excluded from the result.
 *
 * For demonstration purposes, let's list all categories within the Accessories category, but exclude exactly
 * the Wireless headphones subcategory.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excluding(
 *                 attributeEquals("code", "wireless-headphones")
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * The category Wireless Headphones and all its subcategories will not be shown in the results list.
 *
 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
 * is a product assigned to a category), the excluding constraint is evaluated against the hierarchical entity
 * (category), but affects the queried non-hierarchical entities (products). It excludes all products referencing
 * categories that satisfy the excluding inner constraints.
 *
 * Let's go back to our example query that excludes the Wireless Headphones category subtree. To list all products
 * available in the Accessories category except those related to the Wireless Headphones category or its subcategories,
 * issue the following query:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excluding(
 *                 attributeEquals("code", "wireless-headphones")
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * You can see that wireless headphone products like Huawei FreeBuds 4, Jabra Elite 3 or Adidas FWD-02 Sport are not
 * present in the listing.
 *
 * When the product is assigned to two categories - one excluded and one part of the visible category tree, the product
 * remains in the result. See the example.
 *
 * <strong>The lookup stops at the first node that satisfies the constraint!</strong>
 *
 * The hierarchical query traverses from the root nodes to the leaf nodes. For each of the nodes, the engine checks
 * whether the excluding constraint is satisfied valid, and if so, it excludes that hierarchy node and all of its child
 * nodes (entire subtree).
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#excluding">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "excluding",
	shortDescription = "The constraint narrows hierarchy within parent constraint to exclude specified hierarchy subtrees from search.",
	userDocsLink = "/documentation/query/filtering/hierarchy#excluding",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyExcluding extends AbstractFilterConstraintContainer implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -6950287451642746676L;
	private static final String CONSTRAINT_NAME = "excluding";

	@Creator
	public HierarchyExcluding(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, NO_ARGS, filtering);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from {@link HierarchyWithin}
	 * query.
	 */
	@Nonnull
	public FilterConstraint[] getFiltering() {
		return getChildren();
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Override
	public boolean isApplicable() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return this;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyExcluding doesn't accept other than filtering constraints!"
		);
		return new HierarchyExcluding(children);
	}
}
