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
import io.evitadb.api.query.HierarchyConstraint;

/**
 * Marker interface for filter constraints that refine the behavior of hierarchy queries by narrowing, excluding, or
 * restricting the scope of hierarchical subtrees. These constraints must be nested within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints and cannot appear as standalone filter constraints.
 *
 * **Purpose**
 *
 * `HierarchySpecificationFilterConstraint` identifies constraints that modify how hierarchical subtrees are selected,
 * traversed, or filtered within a hierarchy query. Unlike {@link HierarchyFilterConstraint}, which establishes the
 * hierarchical scope of a query, specification constraints provide fine-grained control over which portions of the
 * hierarchy tree are included or excluded from results.
 *
 * **Constraint Containment**
 *
 * Specification constraints are only valid as children of hierarchy filter constraints:
 * - **Valid**: `hierarchyWithin("categories", entityPrimaryKeyInSet(5), directRelation(), excluding(...))`
 * - **Invalid**: `and(directRelation(), attributeEquals("name", "test"))` (specification constraint used standalone)
 *
 * The constraint validation logic enforces this containment rule at query construction time, preventing invalid query
 * structures that would otherwise fail at execution.
 *
 * **Specification Constraint Types**
 *
 * Implementations fall into three behavioral categories:
 *
 * 1. **Traversal limiters** (restrict how deep the hierarchy is traversed):
 *    - {@link HierarchyDirectRelation}: limits results to immediate children only, preventing transitive descent
 *
 * 2. **Inclusion filters** (require nodes to satisfy conditions):
 *    - {@link HierarchyHaving}: stops traversal at the first node that doesn't satisfy the filter, excluding that node
 *      and all its descendants (top-down early termination)
 *    - {@link HierarchyAnyHaving}: requires at least one node in a subtree to satisfy the filter, examining the entire
 *      subtree before deciding inclusion (bottom-up evaluation)
 *
 * 3. **Exclusion filters** (remove subtrees from results):
 *    - {@link HierarchyExcluding}: stops traversal at the first node that satisfies the filter, excluding that node
 *      and all its descendants (inverse of `having`)
 *    - {@link HierarchyExcludingRoot}: excludes the specified parent node from results but includes its children
 *      (useful for "category landing page" scenarios)
 *
 * **Behavioral Interactions**
 *
 * Multiple specification constraints can be combined within a single hierarchy query:
 * - At most one `directRelation` constraint is meaningful (additional instances have no effect)
 * - At most one `having` constraint is evaluated (subsequent instances are ignored)
 * - At most one `anyHaving` constraint is evaluated (subsequent instances are ignored)
 * - At most one `excluding` constraint is evaluated (subsequent instances are ignored)
 * - At most one `excludingRoot` constraint is evaluated (subsequent instances are ignored)
 *
 * **Traversal Semantics**
 *
 * Specification constraints affect hierarchical traversal order and short-circuit behavior:
 * - `having` and `excluding` enable early termination: traversal stops at the first node that fails `having` or matches
 *   `excluding`, preventing examination of descendants
 * - `anyHaving` requires full subtree examination: every node must be evaluated before determining whether the subtree
 *   satisfies the condition
 * - `directRelation` prevents traversal beyond immediate children, regardless of other constraints
 * - `excludingRoot` is evaluated after the parent node is identified but before including it in results
 *
 * **Self-Hierarchical vs. Reference-Hierarchical Queries**
 *
 * Specification constraints behave differently depending on query mode:
 * - **Self-hierarchical queries** (e.g., querying `Category` entities): specification constraints evaluate against the
 *   queried entities themselves, filtering the category tree
 * - **Reference-hierarchical queries** (e.g., querying `Product` entities that reference `Category` entities):
 *   specification constraints evaluate against the referenced hierarchical entities (categories), but affect which
 *   non-hierarchical entities (products) are returned
 *
 * **Example Usage**
 *
 * ```java
 * // Direct children only (no transitive descendants)
 * hierarchyWithin("categories", entityPrimaryKeyInSet(5), directRelation())
 *
 * // Include subtrees where every ancestor is valid
 * hierarchyWithinRoot("categories", having(attributeInRange("validity", now())))
 *
 * // Include subtrees containing at least one "featured" category
 * hierarchyWithin("categories", entityPrimaryKeyInSet(5), anyHaving(attributeEquals("featured", true)))
 *
 * // Exclude "sale" category subtree
 * hierarchyWithinRootSelf(excluding(attributeEquals("code", "sale")))
 *
 * // Exclude parent node itself but include children
 * hierarchyWithin("categories", attributeEquals("code", "accessories"), excludingRoot())
 *
 * // Combine multiple specifications
 * hierarchyWithin(
 *     "categories",
 *     attributeEquals("code", "electronics"),
 *     having(attributeEquals("status", "ACTIVE")),
 *     excluding(attributeEquals("clearance", true)),
 *     excludingRoot()
 * )
 * ```
 *
 * **Design Rationale**
 *
 * The separation between `HierarchyFilterConstraint` and `HierarchySpecificationFilterConstraint` serves several
 * purposes:
 * 1. **Type safety**: prevents specification constraints from appearing where they would be meaningless
 * 2. **API clarity**: external API generators (GraphQL, REST, gRPC) use this distinction to generate correct schemas
 *    with proper nesting rules
 * 3. **Query validation**: the constraint processor can enforce structural rules at application startup rather than at
 *    query execution time
 * 4. **Performance**: the engine can apply optimizations knowing that specification constraints only appear within
 *    hierarchy filter containers
 *
 * **Relationship with HierarchyReferenceSpecificationFilterConstraint**
 *
 * {@link HierarchyReferenceSpecificationFilterConstraint} is a complementary interface for constraints that target
 * referenced entities within hierarchy queries (e.g., filtering products by category attributes in a hierarchy-aware
 * manner). Both interfaces can be used within the same hierarchy query to achieve complex filtering logic.
 *
 * @see HierarchyFilterConstraint parent container constraints that establish hierarchical scope
 * @see HierarchyWithin hierarchy filter with specified root nodes
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @see HierarchyReferenceSpecificationFilterConstraint constraints targeting referenced entities
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchySpecificationFilterConstraint
	extends FilterConstraint, HierarchyConstraint<FilterConstraint> {
}
