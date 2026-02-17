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
 * The `excludingRoot` constraint removes the parent node itself from hierarchy query results while retaining all its
 * children and descendants. This differs from {@link HierarchyExcluding}, which removes both the matching node and all
 * its descendants. The constraint can only be used within {@link HierarchyWithin} parent constraints and has no effect
 * within {@link HierarchyWithinRoot}.
 *
 * **Syntax**
 *
 * ```evitaql
 * excludingRoot()
 * ```
 *
 * **Arguments**
 *
 * None. This is a marker constraint with no parameters.
 *
 * **Behavioral Contract**
 *
 * By default, hierarchy queries return the specified parent node(s) plus all their descendants. The `excludingRoot`
 * constraint modifies this behavior to exclude the parent node itself while keeping its children:
 *
 * ```
 * Default behavior:
 *   hierarchyWithin("categories", entityPrimaryKeyInSet(5))
 *   Returns: [5, 6, 7, 8, 9, ...]  (parent 5 + all descendants)
 *
 * With excludingRoot:
 *   hierarchyWithin("categories", entityPrimaryKeyInSet(5), excludingRoot())
 *   Returns: [6, 7, 8, 9, ...]  (only descendants, parent 5 excluded)
 * ```
 *
 * **Query Mode Behavior**
 *
 * The constraint behaves differently depending on whether the query is self-hierarchical or reference-hierarchical:
 *
 * 1. **Self-hierarchical mode**: omits the parent category from results but includes all its child categories. For
 *    example, querying categories within "Accessories" with `excludingRoot()` returns all subcategories of Accessories
 *    but not the Accessories category itself.
 *
 * 2. **Reference-hierarchical mode**: excludes products directly assigned to the parent category but includes products
 *    assigned to any of its child categories. For example, querying products within "Keyboards" with `excludingRoot()`
 *    excludes products directly assigned to "Keyboards" but includes products assigned to "Mechanical Keyboards",
 *    "Wireless Keyboards", etc.
 *
 * **Incompatibility with HierarchyWithinRoot**
 *
 * The `excludingRoot` constraint is meaningless and unsupported within {@link HierarchyWithinRoot} because there is no
 * actual parent node to exclude (the "virtual" root is a conceptual construct, not an entity). Attempting to use
 * `excludingRoot` within `hierarchyWithinRoot` results in a validation error during query construction.
 *
 * **Difference from HierarchyExcluding**
 *
 * | Aspect | HierarchyExcluding | HierarchyExcludingRoot |
 * |--------|-------------------|------------------------|
 * | Scope | Removes matching subtrees (node + descendants) | Removes only parent node |
 * | Filter parameter | Required (identifies nodes to exclude) | Not allowed (always parent) |
 * | Traversal behavior | Early termination at matching nodes | No traversal impact |
 * | Use case | Remove entire category branches | Implement "category landing page" without products |
 *
 * **Use Cases**
 *
 * Common scenarios for `excludingRoot`:
 * - **Category landing pages**: display products in subcategories but not products assigned to the landing page
 *   category itself
 * - **Hierarchical navigation**: show child categories without treating the parent as a selectable option
 * - **Data organization**: use parent categories as organizational containers without direct entity assignment
 * - **UI/UX patterns**: implement "drill-down" interfaces where selecting a category shows only its children
 *
 * **Multiple Category Assignment**
 *
 * In reference-hierarchical mode, if a product is assigned to both the parent category and one of its child categories,
 * the product remains in the result because it has at least one assignment to a visible (non-excluded) category:
 *
 * ```
 * Category tree:
 *   - Electronics (ID: 5, excluded by excludingRoot)
 *     - Smartphones (ID: 10)
 *     - Tablets (ID: 11)
 *
 * Product X: assigned to both "Electronics" (5) and "Smartphones" (10)
 * Result: Product X is INCLUDED because "Smartphones" assignment is visible
 * ```
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: return subcategories of Accessories but not Accessories itself
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories"),
 *             excludingRoot()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Reference-hierarchical: return products in Keyboards subcategories but not in Keyboards directly
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "keyboards"),
 *             excludingRoot()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * // If Keyboards has 20 products total: 16 directly assigned, 4 in subcategories
 * // Result: returns only the 4 products in subcategories
 *
 * // Combine with other specifications
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             excludingRoot(),
 *             excluding(attributeEquals("clearance", true)),
 *             having(attributeEquals("status", "ACTIVE"))
 *         )
 *     )
 * )
 *
 * // INVALID: cannot use with hierarchyWithinRoot (no actual parent to exclude)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             excludingRoot()  // ERROR: validation fails
 *         )
 *     )
 * )
 * ```
 *
 * **Practical Example: Category Landing Pages**
 *
 * Consider an e-commerce site where the "Electronics" category has products directly assigned plus subcategories like
 * "Smartphones" and "Tablets". The landing page for Electronics should show subcategory links and products from those
 * subcategories, but NOT products directly assigned to Electronics (those might be miscategorized or organizational
 * placeholders):
 *
 * ```java
 * // Electronics landing page: products in subcategories only
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             excludingRoot()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("name")),
 *         page(1, 20)
 *     )
 * )
 * ```
 *
 * **Combining with DirectRelation**
 *
 * The `excludingRoot` and `directRelation` constraints can be combined to return only direct children of the parent
 * (excluding both the parent itself and transitive descendants):
 *
 * ```java
 * hierarchyWithin(
 *     "categories",
 *     attributeEquals("code", "electronics"),
 *     directRelation(),
 *     excludingRoot()
 * )
 * // Returns: direct child categories only (Smartphones, Tablets)
 * // Excludes: Electronics itself, grandchildren (e.g., Android Phones under Smartphones)
 * ```
 *
 * **Performance Impact**
 *
 * The `excludingRoot` constraint has minimal performance overhead. It's implemented as a simple post-filter after
 * hierarchy traversal rather than affecting traversal logic itself.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#excluding-root)
 *
 * @see HierarchyExcluding constraint for excluding entire subtrees
 * @see HierarchyWithin primary hierarchy filter constraint
 * @see HierarchyDirectRelation constraint for limiting to direct children only
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "excludingRoot",
	shortDescription = "The constraint excludes the root node of the hierarchy subtree from results while keeping all its children and descendants.",
	userDocsLink = "/documentation/query/filtering/hierarchy#excluding-root",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyExcludingRoot extends AbstractFilterConstraintLeaf
	implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3965082821350063527L;
	private static final String CONSTRAINT_NAME = "excludingRoot";

	private HierarchyExcludingRoot(@Nonnull Serializable... arguments) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyExcludingRoot() {
		super(CONSTRAINT_NAME);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyExcludingRoot(newArguments);
	}
}
