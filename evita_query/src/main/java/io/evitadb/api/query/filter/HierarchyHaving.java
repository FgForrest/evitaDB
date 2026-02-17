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
 * The `having` constraint filters hierarchy subtrees by requiring each node along the traversal path from root to leaf
 * to satisfy specified filter conditions. When a node fails to satisfy the constraint, that node and all its
 * descendants are excluded from results. This enables conditional visibility of hierarchy branches and can only be used
 * within {@link HierarchyWithin} or {@link HierarchyWithinRoot} parent constraints.
 *
 * **Syntax**
 *
 * ```evitaql
 * having(
 *     filtering: FilterConstraint+
 * )
 * ```
 *
 * **Arguments**
 *
 * - `filtering` (required): one or more filter constraints that must be satisfied by each hierarchy node for its
 *   subtree to remain visible. Multiple constraints are combined with implicit logical AND. The lookup traverses from
 *   root to leaf, stopping at the first node that fails the constraints and excluding that node plus all descendants.
 *
 * **Early Termination Semantics**
 *
 * The `having` constraint implements top-down early termination during hierarchy traversal:
 * 1. Traversal proceeds from root nodes toward leaf nodes
 * 2. For each node along the path, the engine evaluates whether it satisfies the `having` filter
 * 3. If a node fails the filter, that node and its entire subtree are excluded from results
 * 4. Traversal does NOT continue into the excluded subtree (descendants are not evaluated)
 *
 * This "gate-keeper" behavior ensures that if an ancestor fails the `having` constraint, all its descendants are
 * automatically excluded regardless of whether they would individually pass the filter.
 *
 * **Difference from Excluding**
 *
 * The `having` and `excluding` constraints are semantic opposites:
 * - `having(condition)`: stops at first node that FAILS the condition, excluding that subtree (inclusion filter)
 * - `excluding(condition)`: stops at first node that MATCHES the condition, excluding that subtree (exclusion filter)
 * - `having(condition)` is semantically equivalent to `excluding(not(condition))`
 *
 * The `having` constraint exists separately for improved query readability when expressing inclusion logic rather than
 * exclusion logic.
 *
 * **Query Mode Behavior**
 *
 * The constraint behaves differently depending on whether the query is self-hierarchical or reference-hierarchical:
 *
 * 1. **Self-hierarchical mode**: evaluates against the queried entities themselves. Categories failing the `having`
 *    filter and all their subcategories are removed from results.
 *
 * 2. **Reference-hierarchical mode**: evaluates against the referenced hierarchical entities but affects the queried
 *    non-hierarchical entities. Products referencing categories that fail the `having` filter (or any ancestors of
 *    those categories) are removed from results.
 *
 * **Multiple Category Assignment**
 *
 * In reference-hierarchical mode, when a product is assigned to multiple categories and only some pass the `having`
 * filter, the product remains in the result if at least one of its assigned categories is within the visible part of
 * the tree:
 *
 * ```
 * Category tree with validity constraints:
 *   - Electronics (valid year-round)
 *     - Smartphones (valid year-round)
 *     - Christmas Electronics (valid Dec 1-24 only)
 *
 * Product "Garmin Vivosmart 5": assigned to both "Smartphones" and "Christmas Electronics"
 * Query time: October 1st with having(attributeInRange("validity", now()))
 * Result: Product is INCLUDED because "Smartphones" passes the validity check
 * ```
 *
 * **Use Cases**
 *
 * Common scenarios for `having`:
 * - **Temporal visibility**: show only categories/products valid during a specific time period (seasonal sales,
 *   promotional periods)
 * - **Access control**: display only categories accessible to the current user role or permission level
 * - **Status filtering**: show only active/published categories and their products
 * - **Localization**: display only categories with translations for the current locale
 * - **Feature flags**: conditionally show categories based on feature toggles or A/B testing flags
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: return only categories valid at specific time
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories"),
 *             having(
 *                 or(
 *                     attributeIsNull("validity"),
 *                     attributeInRange("validity", ZonedDateTime.now())
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * // If "Christmas Electronics" is valid only Dec 1-24, it's excluded in October
 * // All its subcategories are also excluded (even if individually valid)
 *
 * // Reference-hierarchical: products in valid categories only
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             having(
 *                 or(
 *                     attributeIsNull("validity"),
 *                     attributeInRange("validity", ZonedDateTime.now())
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * // Products in "Christmas Electronics" subtree are excluded in October
 *
 * // Status-based filtering (only active categories)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             having(attributeEquals("status", "ACTIVE"))
 *         )
 *     )
 * )
 *
 * // Access control (only categories for current user role)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             having(
 *                 or(
 *                     attributeIsNull("requiredRole"),
 *                     attributeEquals("requiredRole", currentUserRole)
 *                 )
 *             )
 *         )
 *     )
 * )
 *
 * // Combine with other specifications
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             having(attributeEquals("status", "ACTIVE")),
 *             excluding(attributeEquals("clearance", true))
 *         )
 *     )
 * )
 * ```
 *
 * **Practical Example: Seasonal Category Visibility**
 *
 * Consider an e-commerce site with a "Holiday" category tree containing subcategories like "Christmas", "Halloween",
 * "Easter", each with a validity date range. Outside the holiday season, these categories should be hidden:
 *
 * ```java
 * // Show only currently valid holiday categories
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "holidays"),
 *             having(attributeInRange("validity", ZonedDateTime.now()))
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("name", "validity"))
 *     )
 * )
 * // In July: Christmas (Dec 1-25) and Easter (April) are excluded
 * // In December: Christmas is visible but Easter remains excluded
 * ```
 *
 * **Combining with AnyHaving**
 *
 * While `having` requires every ancestor to pass the filter, {@link HierarchyAnyHaving} only requires at least one
 * node in the subtree to pass. These can be combined for complex filtering logic:
 *
 * ```java
 * hierarchyWithinRoot(
 *     "categories",
 *     having(attributeEquals("status", "ACTIVE")),  // All ancestors must be active
 *     anyHaving(attributeEquals("featured", true))  // At least one descendant must be featured
 * )
 * ```
 *
 * **Performance Characteristics**
 *
 * The early termination behavior makes `having` highly efficient for pruning large subtrees. When a high-level
 * category fails the filter, the engine avoids evaluating potentially thousands of descendants.
 *
 * @see HierarchyExcluding inverse constraint for excluding subtrees based on filter
 * @see HierarchyAnyHaving variant requiring at least one subtree node to satisfy filter
 * @see HierarchyWithin primary hierarchy filter constraint
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The constraint narrows the hierarchy within a parent constraint to include only the subtrees that satisfy the inner filter constraint.",
	userDocsLink = "/documentation/query/filtering/hierarchy#having",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyHaving extends AbstractFilterConstraintContainer
	implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -6950287451642746676L;
	private static final String CONSTRAINT_NAME = "having";

	@Creator
	public HierarchyHaving(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, NO_ARGS, filtering);
	}

	/**
	 * Returns filtering constraints that must be satisfied by hierarchy nodes for their subtrees to be included
	 * in the {@link HierarchyWithin} query result.
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
		return new HierarchyHaving(getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyHaving doesn't accept other than filtering constraints!"
		);
		return new HierarchyHaving(children);
	}
}
