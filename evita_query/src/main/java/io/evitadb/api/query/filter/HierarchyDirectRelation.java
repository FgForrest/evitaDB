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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `directRelation` constraint limits hierarchy query results to direct children of the parent node(s) only,
 * excluding transitive descendants. By default, hierarchy queries return both direct children and all transitive
 * descendants (children of children, recursively to leaf nodes). This constraint modifies that behavior to return only
 * the immediate children. It can only be used within {@link HierarchyWithin} or {@link HierarchyWithinRoot} parent
 * constraints.
 *
 * **Syntax**
 *
 * ```evitaql
 * directRelation()
 * ```
 *
 * **Arguments**
 *
 * None. This is a marker constraint with no parameters.
 *
 * **Behavioral Contract**
 *
 * Without `directRelation`, hierarchy queries return the full subtree:
 *
 * ```
 * Default behavior:
 *   hierarchyWithin("categories", entityPrimaryKeyInSet(5))
 *   Returns: [5, 6, 7, 8, 9, 10, ...]  (parent 5 + direct children 6,7 + grandchildren 8,9,10 + ...)
 *
 * With directRelation:
 *   hierarchyWithin("categories", entityPrimaryKeyInSet(5), directRelation())
 *   Returns: [5, 6, 7]  (parent 5 + direct children 6,7 only, no grandchildren)
 * ```
 *
 * **Query Mode Behavior**
 *
 * The constraint behaves differently depending on whether the query is self-hierarchical or reference-hierarchical:
 *
 * 1. **Self-hierarchical mode**: returns only the direct children of the specified parent node(s). For example,
 *    querying categories within "Electronics" with `directRelation()` returns only immediate subcategories like
 *    "Computers", "Smartphones", "Tablets" but not deeper levels like "Laptops" (under Computers) or "iPhone" (under
 *    Smartphones).
 *
 * 2. **Reference-hierarchical mode**: returns only non-hierarchical entities directly assigned to the specified parent
 *    category (not assigned to child categories). For example, querying products within "Electronics" with
 *    `directRelation()` returns only products directly assigned to "Electronics" but not products assigned to
 *    "Smartphones", "Computers", or deeper categories.
 *
 * **HierarchyWithinRoot Behavior**
 *
 * When used with {@link HierarchyWithinRoot}:
 *
 * - **Self-hierarchical mode**: returns only top-level categories (categories with no parent). This is useful for
 *   building navigation menus that show only the first level of the category tree.
 *
 * - **Reference-hierarchical mode**: the constraint is meaningless and has no effect because no entity can be directly
 *   assigned to the invisible "virtual" root. All entities are assigned to actual hierarchy nodes, not to the virtual
 *   root, so using `directRelation()` with `hierarchyWithinRoot` in reference-hierarchical mode returns an empty
 *   result.
 *
 * **Combining with Other Specifications**
 *
 * The `directRelation` constraint can be combined with other {@link HierarchySpecificationFilterConstraint} instances:
 * - Can coexist with `having` (apply filter to direct children only)
 * - Can coexist with `excluding` (exclude specific direct children)
 * - Can coexist with `excludingRoot` (return direct children but not parent)
 * - Only one `directRelation` constraint is evaluated per query (additional instances have no effect)
 *
 * **Use Cases**
 *
 * Common scenarios for `directRelation`:
 * - **Navigation menus**: show only top-level or first-level categories without deep nesting
 * - **Breadcrumb navigation**: display only immediate child categories when drilling down
 * - **Category landing pages**: show products directly assigned to a category (not in subcategories)
 * - **Hierarchical faceting**: provide facet values only for the next level of the hierarchy
 * - **Performance optimization**: limit result sets when deep hierarchy traversal is not needed
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: return only direct subcategories of Electronics
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "electronics"),
 *             directRelation()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code", "name"))
 *     )
 * )
 * // Returns: [Electronics, Computers, Smartphones, Tablets] (no Laptops, iPhone, etc.)
 *
 * // Self-hierarchical: return only top-level categories
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             directRelation()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code", "name"))
 *     )
 * )
 * // Returns: [Electronics, Fashion, Home & Garden, ...] (only categories with parent = null)
 *
 * // Reference-hierarchical: products directly assigned to Smartwatches
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "smartwatches"),
 *             directRelation()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code", "name"))
 *     )
 * )
 * // Returns: products assigned specifically to "Smartwatches" category
 * // Excludes: products assigned to "Fitness Trackers", "Smart Rings", etc. under Smartwatches
 *
 * // Reference-hierarchical with excludingRoot: only products in direct subcategories
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             directRelation(),
 *             excludingRoot()
 *         )
 *     )
 * )
 * // Returns: products in "Computers", "Smartphones", "Tablets"
 * // Excludes: products directly in "Electronics" and products in deeper levels
 *
 * // Combine with having: only active direct children
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "electronics"),
 *             directRelation(),
 *             having(attributeEquals("status", "ACTIVE"))
 *         )
 *     )
 * )
 * // Returns: direct subcategories of Electronics that are marked as ACTIVE
 *
 * // Combine with excluding: direct children except clearance
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             directRelation(),
 *             excluding(attributeEquals("clearance", true))
 *         )
 *     )
 * )
 * // Returns: products in direct subcategories of Electronics except clearance categories
 * ```
 *
 * **Practical Example: Two-Level Navigation Menu**
 *
 * Consider building a mega-menu that shows top-level categories plus their immediate children, but not deeper levels:
 *
 * ```java
 * // Top-level categories
 * List<Category> topLevel = query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(directRelation())
 *     )
 * );
 *
 * // For each top-level category, fetch direct children
 * for (Category parent : topLevel) {
 *     List<Category> children = query(
 *         collection("Category"),
 *         filterBy(
 *             hierarchyWithinSelf(
 *                 entityPrimaryKeyInSet(parent.getId()),
 *                 directRelation(),
 *                 excludingRoot()  // Don't include parent in children list
 *             )
 *         )
 *     );
 *     // Render: parent -> children
 * }
 * ```
 *
 * **Parent Inclusion**
 *
 * By default, hierarchy queries include the specified parent node(s) in results. The `directRelation` constraint does
 * NOT exclude the parent. To exclude the parent node while showing only direct children, combine `directRelation()`
 * with `excludingRoot()`:
 *
 * ```java
 * hierarchyWithin(
 *     "categories",
 *     attributeEquals("code", "electronics"),
 *     directRelation(),
 *     excludingRoot()
 * )
 * // Returns: only direct children of Electronics (Computers, Smartphones, etc.)
 * // Excludes: Electronics itself
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#direct-relation)
 *
 * @see HierarchyWithin primary hierarchy filter constraint
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @see HierarchyExcludingRoot constraint for excluding parent node from results
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "directRelation",
	shortDescription = "The constraint limits hierarchy within parent constraint to take only directly related entities into an account.",
	userDocsLink = "/documentation/query/filtering/hierarchy#direct-relation",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyDirectRelation extends AbstractFilterConstraintLeaf
	implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3959881131308135131L;
	private static final String CONSTRAINT_NAME = "directRelation";

	private HierarchyDirectRelation(@Nonnull Serializable... arguments) {
		// missing "hierarchy" prefix because this query can be used only within some other hierarchy query,
		// it would be unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyDirectRelation() {
		super(CONSTRAINT_NAME);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyDirectRelation(newArguments);
	}

}
