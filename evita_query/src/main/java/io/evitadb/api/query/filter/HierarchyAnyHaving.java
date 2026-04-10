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
 * The `anyHaving` constraint filters hierarchy subtrees by requiring at least one node within each subtree to satisfy
 * specified filter conditions. Unlike {@link HierarchyHaving} which stops at the first failing node, `anyHaving`
 * examines entire subtrees to determine whether they contain any matching nodes. This enables "existence" queries over
 * hierarchy trees and can only be used within {@link HierarchyWithin} or {@link HierarchyWithinRoot} parent
 * constraints.
 *
 * **Syntax**
 *
 * ```evitaql
 * anyHaving(
 *     filtering: FilterConstraint+
 * )
 * ```
 *
 * **Arguments**
 *
 * - `filtering` (required): one or more filter constraints that must be satisfied by at least one node in the subtree
 *   (including the subtree root node itself) for the subtree to remain visible. Multiple constraints are combined with
 *   implicit logical AND. The lookup examines the entire subtree before making the inclusion decision (no early
 *   termination).
 *
 * **Full Subtree Evaluation**
 *
 * Unlike `having` and `excluding` which use early termination, `anyHaving` evaluates entire subtrees:
 * 1. For each potential subtree root, traverse all descendants
 * 2. Check if any node (including the root) satisfies the `anyHaving` filter
 * 3. If at least one node matches, include the entire subtree in results
 * 4. If no nodes match, exclude the entire subtree from results
 *
 * This "bottom-up" evaluation allows parent nodes to remain visible based on properties of their descendants, even if
 * the parents themselves don't satisfy the filter.
 *
 * **Difference from Having**
 *
 * | Aspect | HierarchyHaving | HierarchyAnyHaving |
 * |--------|-----------------|---------------------|
 * | Evaluation strategy | Top-down with early termination | Bottom-up with full subtree scan |
 * | Requirement | Every ancestor must satisfy filter | At least one node in subtree must satisfy filter |
 * | Parent visibility | Parent must pass filter to be visible | Parent visible if any descendant passes filter |
 * | Performance | Fast (early termination) | Slower (full subtree evaluation) |
 * | Use case | Gate-keeper filtering (all ancestors must be valid) | Existence queries (subtree contains matching nodes) |
 *
 * **Query Mode Behavior**
 *
 * The constraint behaves differently depending on whether the query is self-hierarchical or reference-hierarchical:
 *
 * 1. **Self-hierarchical mode**: evaluates against the queried entities themselves. Categories are included if they or
 *    any of their descendants satisfy the `anyHaving` filter.
 *
 * 2. **Reference-hierarchical mode**: evaluates against the referenced hierarchical entities but affects the queried
 *    non-hierarchical entities. Products are included if they reference categories within subtrees where at least one
 *    category satisfies the `anyHaving` filter.
 *
 * **Combining with Having**
 *
 * The `having` and `anyHaving` constraints can be combined to express complex filtering logic:
 * - `having`: ensures every ancestor in the path passes a filter (e.g., all categories must be active)
 * - `anyHaving`: ensures at least one node in the subtree passes a different filter (e.g., subtree contains featured
 *   products)
 *
 * ```java
 * hierarchyWithinRoot(
 *     "categories",
 *     having(attributeEquals("status", "ACTIVE")),  // All ancestors must be active
 *     anyHaving(
 *         referenceHaving(
 *             "products",
 *             entityHaving(attributeEquals("featured", true))  // At least one product must be featured
 *         )
 *     )
 * )
 * // Returns: active categories containing at least one featured product (directly or in descendants)
 * ```
 *
 * **Use Cases**
 *
 * Common scenarios for `anyHaving`:
 * - **Non-empty subtrees**: show only categories that contain products (directly or transitively)
 * - **Featured content**: display category trees where at least one item is marked as featured/promoted
 * - **Search results**: show category hierarchies containing search matches
 * - **Availability filtering**: include category trees with at least one in-stock product
 * - **Parent-child relationships**: find parent categories based on child properties
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: categories containing active products (directly or in descendants)
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             entityPrimaryKeyInSet(1, 2, 3),
 *             having(attributeEquals("status", "ACTIVE")),
 *             anyHaving(
 *                 referenceHaving(
 *                     "products",
 *                     entityHaving(attributeEquals("status", "ACTIVE"))
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * // Returns: categories 1, 2, 3 that are ACTIVE and contain at least one ACTIVE product
 * // Includes parent categories if their descendants have active products
 *
 * // Reference-hierarchical: products in categories marked as "favorites" or parent of such
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             having(attributeEquals("status", "ACTIVE")),
 *             anyHaving(attributeEquals("favorite", true))
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * // Returns: products in category trees containing at least one "favorite" category
 * // If "Smartwatches" is favorite, includes products in parent "Electronics" too
 *
 * // Non-empty categories: only categories with products
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             anyHaving(
 *                 referenceHaving("products", entityPrimaryKeyInSet())  // Has any products
 *             )
 *         )
 *     )
 * )
 *
 * // Search-driven hierarchy: categories containing search matches
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             anyHaving(
 *                 referenceHaving(
 *                     "products",
 *                     entityHaving(attributeContains("name", "wireless"))
 *                 )
 *             )
 *         )
 *     )
 * )
 * // Returns: categories (and their ancestors) containing products with "wireless" in name
 *
 * // Combine multiple specifications
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             having(attributeEquals("status", "ACTIVE")),  // All categories must be active
 *             excluding(attributeEquals("clearance", true)),  // Exclude clearance subtrees
 *             anyHaving(attributeEquals("featured", true))  // Must contain featured category
 *         )
 *     )
 * )
 * ```
 *
 * **Practical Example: Category Tree with Product Counts**
 *
 * Consider building a navigation menu where empty categories (categories with no products in their subtree) should be
 * hidden:
 *
 * ```java
 * // Show only categories with at least one product (directly or in descendants)
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             anyHaving(
 *                 referenceHaving(
 *                     "products",
 *                     entityPrimaryKeyInSet()  // Non-empty product reference
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("name")),
 *         hierarchyOfSelf(
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("name")),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * // Returns: full category tree excluding leaf categories with no products
 * // Parent categories remain visible if descendants have products
 * ```
 *
 * **Multiple Category Assignment**
 *
 * In reference-hierarchical mode, if a product is assigned to multiple categories and only some are within subtrees
 * satisfying `anyHaving`, the product remains in the result if at least one assignment is within a visible subtree.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#anyHaving)
 *
 * @see HierarchyHaving constraint requiring every ancestor to satisfy filter
 * @see HierarchyExcluding constraint for excluding subtrees based on filter
 * @see HierarchyWithin primary hierarchy filter constraint
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "anyHaving",
	shortDescription = "The constraint narrows the hierarchy within a parent constraint to include only the subtrees whose at least one member satisfies the inner filter constraint.",
	userDocsLink = "/documentation/query/filtering/hierarchy#anyHaving",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyAnyHaving extends AbstractFilterConstraintContainer
	implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -7926794636918674168L;
	private static final String CONSTRAINT_NAME = "anyHaving";

	@Creator
	public HierarchyAnyHaving(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, NO_ARGS, filtering);
	}

	/**
	 * Returns filtering constraints that must be satisfied by at least one child (or the node itself) for the
	 * hierarchy subtree to be included in the {@link HierarchyWithin} query result.
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
		return new HierarchyAnyHaving(getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyAnyHaving doesn't accept other than filtering constraints!"
		);
		return new HierarchyAnyHaving(children);
	}
}