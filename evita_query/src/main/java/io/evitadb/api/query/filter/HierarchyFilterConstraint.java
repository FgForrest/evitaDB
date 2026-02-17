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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HierarchyConstraint;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Marker interface for all filter constraints that restrict queries based on hierarchical entity relationships.
 * This interface identifies the primary hierarchy filtering constraints {@link HierarchyWithin} and
 * {@link HierarchyWithinRoot}, which define the scope of hierarchical queries by selecting subtrees of hierarchical
 * entity structures.
 *
 * **Purpose**
 *
 * `HierarchyFilterConstraint` distinguishes the top-level hierarchy filtering constraints from the nested specification
 * constraints ({@link HierarchySpecificationFilterConstraint}) that refine their behavior. Only constraints implementing
 * this interface can appear as direct children of {@link FilterBy} or within logical containers like {@link And},
 * {@link Or}, or {@link Not}. They establish the hierarchical scope that specification constraints then modify.
 *
 * **Hierarchy Query Model**
 *
 * evitaDB supports two hierarchical query modes:
 *
 * 1. **Self-hierarchical queries**: when the queried entity collection is itself hierarchical (e.g., `Category` entities
 *    forming a category tree), the hierarchy constraints filter the entities themselves based on their position in the
 *    tree.
 * 2. **Reference-hierarchical queries**: when the queried entities reference a hierarchical entity type (e.g., `Product`
 *    entities referencing hierarchical `Category` entities), the hierarchy constraints evaluate against the referenced
 *    hierarchical entities but return the queried non-hierarchical entities.
 *
 * **Behavioral Contract**
 *
 * All implementations must provide:
 * - {@link #getReferenceName()}: identifies which reference schema represents the hierarchical relationship (empty for
 *   self-hierarchical queries)
 * - {@link #isDirectRelation()}: whether to return only direct children/references or include transitive descendants
 * - {@link #getHavingChildrenFilter()}: filter constraints that hierarchy nodes must satisfy for their subtrees to be
 *   included (traversal stops at first non-matching node)
 * - {@link #getHavingAnyChildFilter()}: filter constraints that at least one node in a subtree must satisfy for that
 *   subtree to be included (examines entire subtree)
 * - {@link #getExcludedChildrenFilter()}: filter constraints identifying subtrees to exclude from results (traversal
 *   stops at first matching node)
 *
 * **Key Behavioral Rules**
 *
 * - By default, hierarchy queries return both the specified parent node(s) and all their direct and transitive children
 * - Only one hierarchy filtering constraint (`hierarchyWithin` or `hierarchyWithinRoot`) is allowed per query
 * - Orphaned hierarchy nodes (nodes with parent references pointing to non-existent entities) never satisfy any
 *   hierarchy query
 * - When a non-hierarchical entity references multiple hierarchical entities and only some match the hierarchy filter,
 *   the entity remains in the result if at least one reference is within the visible part of the tree
 * - The `having` filter stops at the first node that doesn't satisfy the constraint and excludes that node's entire
 *   subtree
 * - The `excluding` filter stops at the first node that satisfies the constraint and excludes that node's entire
 *   subtree
 * - The `anyHaving` filter examines entire subtrees and includes trees where at least one node satisfies the constraint
 *
 * **Typical Implementations**
 *
 * - {@link HierarchyWithin}: restricts results to entities within subtree(s) rooted at specified parent node(s)
 * - {@link HierarchyWithinRoot}: restricts results to entities within the entire hierarchy tree, treating all top-level
 *   nodes as children of an invisible "virtual" root
 *
 * **Integration with Specification Constraints**
 *
 * Hierarchy filter constraints serve as containers for optional {@link HierarchySpecificationFilterConstraint}
 * instances:
 * - {@link HierarchyDirectRelation}: limits results to direct children/references only
 * - {@link HierarchyHaving}: requires each node in the path from root to leaf to satisfy filter conditions
 * - {@link HierarchyAnyHaving}: requires at least one node in each subtree to satisfy filter conditions
 * - {@link HierarchyExcluding}: excludes subtrees whose root nodes match filter conditions
 * - {@link HierarchyExcludingRoot}: excludes the specified parent node itself from results (children remain)
 *
 * **Example Usage**
 *
 * ```java
 * // Self-hierarchical: find all categories under "Accessories"
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories")
 *         )
 *     )
 * )
 *
 * // Reference-hierarchical: find products in "Accessories" category tree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories")
 *         )
 *     )
 * )
 *
 * // With specifications: direct children only, excluding wireless headphones
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             directRelation(),
 *             excluding(attributeEquals("code", "wireless-headphones"))
 *         )
 *     )
 * )
 *
 * // Entire tree from all roots, filtering by validity
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             having(attributeInRange("validity", ZonedDateTime.now()))
 *         )
 *     )
 * )
 * ```
 *
 * **Design Context**
 *
 * This interface extends both {@link FilterConstraint} and {@link HierarchyConstraint}, placing it at the intersection
 * of two classification dimensions in evitaDB's constraint taxonomy:
 * - **Purpose**: it's a `FilterConstraint`, restricting which entities appear in query results
 * - **Property domain**: it's a `HierarchyConstraint`, operating on hierarchical entity relationships
 *
 * The separation between `HierarchyFilterConstraint` and `HierarchySpecificationFilterConstraint` enforces proper
 * constraint nesting and prevents invalid query structures like `having(hierarchyWithin(...))`.
 *
 * @see HierarchyWithin primary hierarchy filtering constraint with specified root nodes
 * @see HierarchyWithinRoot hierarchy filtering constraint for entire tree from all roots
 * @see HierarchySpecificationFilterConstraint nested constraints that refine hierarchy filter behavior
 * @see HierarchyConstraint property-type marker interface for all hierarchy-related constraints
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyFilterConstraint extends FilterConstraint, HierarchyConstraint<FilterConstraint> {

	/**
	 * Returns name of the reference this hierarchy query relates to.
	 * Returns empty value if reference name is not specified and thus the same entity type as "queried" should be used.
	 */
	@Nonnull
	Optional<String> getReferenceName();

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the root entity.
	 */
	boolean isDirectRelation();

	/**
	 * Returns filtering constraints that return entities whose trees should be included in the hierarchy query.
	 */
	@Nonnull
	FilterConstraint[] getHavingChildrenFilter();

	/**
	 * Returns filtering constraints that return entities that have at least one child satisfying the filter
	 * in order for the hierarchy tree to be included in the hierarchy query.
	 */
	@Nonnull
	FilterConstraint[] getHavingAnyChildFilter();

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Nonnull
	FilterConstraint[] getExcludedChildrenFilter();

}
