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
 * The `excluding` constraint removes specified subtrees from hierarchy query results by identifying root nodes that
 * match filter criteria and excluding those nodes plus all their descendants. This is the inverse of the
 * {@link HierarchyHaving} constraint and can only be used within {@link HierarchyWithin} or {@link HierarchyWithinRoot}
 * parent constraints.
 *
 * **Syntax**
 *
 * ```evitaql
 * excluding(
 *     filtering: FilterConstraint+
 * )
 * ```
 *
 * **Arguments**
 *
 * - `filtering` (required): one or more filter constraints that identify hierarchy nodes whose subtrees should be
 *   excluded from results. Multiple constraints are combined with implicit logical AND. The lookup traverses from root
 *   to leaf, stopping at the first node that satisfies the constraints and excluding that node plus all descendants.
 *
 * **Early Termination Semantics**
 *
 * The `excluding` constraint implements early termination during hierarchy traversal:
 * 1. Traversal proceeds from root nodes toward leaf nodes
 * 2. For each node, the engine evaluates whether it matches the `excluding` filter
 * 3. If a node matches, that node and its entire subtree are excluded from results
 * 4. Traversal does NOT continue into the excluded subtree (descendants are not evaluated)
 *
 * This early termination is critical for performance: if a category has thousands of descendants, excluding the
 * category avoids evaluating all those descendants.
 *
 * **Inverse Relationship with Having**
 *
 * The `excluding` constraint is semantically equivalent to `having(not(filtering))`, but exists as a separate
 * constraint for improved readability. Both constraints use early termination, but with opposite conditions:
 * - `having(condition)`: stops at first node that FAILS the condition, excluding that subtree
 * - `excluding(condition)`: stops at first node that MATCHES the condition, excluding that subtree
 *
 * **Query Mode Behavior**
 *
 * The constraint behaves differently depending on whether the query is self-hierarchical or reference-hierarchical:
 *
 * 1. **Self-hierarchical mode**: evaluates against the queried entities themselves. Categories matching the `excluding`
 *    filter and all their subcategories are removed from results.
 *
 * 2. **Reference-hierarchical mode**: evaluates against the referenced hierarchical entities but affects the queried
 *    non-hierarchical entities. Products referencing categories that match the `excluding` filter (or any descendants
 *    of those categories) are removed from results.
 *
 * **Multiple Category Assignment**
 *
 * In reference-hierarchical mode, when a product is assigned to multiple categories and only some are excluded, the
 * product remains in the result if at least one of its assigned categories is within the visible part of the tree:
 *
 * ```
 * Category tree:
 *   - Electronics
 *     - Computers
 *     - Smartphones
 *   - Clearance (excluded)
 *     - Discontinued
 *
 * Product X: assigned to both "Smartphones" and "Discontinued"
 * Result: Product X is INCLUDED because "Smartphones" is visible
 * ```
 *
 * **Combining with Other Specifications**
 *
 * The `excluding` constraint can be combined with other {@link HierarchySpecificationFilterConstraint} instances:
 * - Can coexist with `having` (apply both inclusion and exclusion filters)
 * - Can coexist with `directRelation` (exclude subtrees but only consider direct children)
 * - Can coexist with `excludingRoot` (exclude both subtrees and parent node)
 * - Only one `excluding` constraint is evaluated per query (subsequent instances are ignored)
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: exclude Wireless Headphones category and subcategories
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories"),
 *             excluding(attributeEquals("code", "wireless-headphones"))
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Reference-hierarchical: exclude products in Wireless Headphones subtree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excluding(attributeEquals("code", "wireless-headphones"))
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Exclude multiple subtrees (categories marked as clearance)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             excluding(attributeEquals("clearance", true))
 *         )
 *     )
 * )
 *
 * // Exclude subtrees based on complex filter
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "electronics"),
 *             excluding(
 *                 and(
 *                     attributeEquals("status", "INACTIVE"),
 *                     attributeLessThan("priority", 10)
 *                 )
 *             )
 *         )
 *     )
 * )
 *
 * // Combine excluding with having (only valid categories, exclude clearance)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             having(attributeInRange("validity", ZonedDateTime.now())),
 *             excluding(attributeEquals("clearance", true))
 *         )
 *     )
 * )
 * ```
 *
 * **Use Cases**
 *
 * Common scenarios for `excluding`:
 * - **Promotional exclusions**: hide sale/clearance categories during regular browsing
 * - **Access control**: exclude restricted categories for certain user roles
 * - **Seasonal filtering**: hide categories that are out of season
 * - **Status-based hiding**: exclude inactive or deprecated category subtrees
 * - **Data quality**: exclude test or placeholder categories from production queries
 *
 * **Performance Characteristics**
 *
 * The early termination behavior makes `excluding` highly efficient for pruning large subtrees. When a high-level
 * category with thousands of descendants is excluded, the engine avoids evaluating those thousands of nodes.
 *
 * **Comparison with Not Constraint**
 *
 * Using `excluding(filter)` is NOT equivalent to wrapping the hierarchy query in `not(...)`:
 * - `excluding(filter)`: removes matching subtrees but keeps rest of hierarchy
 * - `not(hierarchyWithin(...))`: inverts the entire hierarchy filter (returns entities NOT in the hierarchy)
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#excluding)
 *
 * @see HierarchyHaving opposite constraint for including subtrees based on filter
 * @see HierarchyExcludingRoot variant that excludes only the parent node, not its children
 * @see HierarchyWithin primary hierarchy filter constraint
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "excluding",
	shortDescription = "The constraint narrows hierarchy within parent constraint to exclude specified hierarchy subtrees from search.",
	userDocsLink = "/documentation/query/filtering/hierarchy#excluding",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyExcluding extends AbstractFilterConstraintContainer
	implements HierarchySpecificationFilterConstraint {
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
		return new HierarchyExcluding(getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyExcluding doesn't accept other than filtering constraints!"
		);
		return new HierarchyExcluding(children);
	}
}
