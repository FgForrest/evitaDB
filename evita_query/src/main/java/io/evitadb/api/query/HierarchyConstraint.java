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

package io.evitadb.api.query;

/**
 * Marker interface for constraints that operate on hierarchical entity structures. This interface is part of
 * evitaDB's property type system, which categorizes constraints based on the type of data they target.
 *
 * **Purpose**
 *
 * `HierarchyConstraint` identifies constraints that can query, filter, order, or require data related to hierarchical
 * relationships between entities. evitaDB supports tree-structured data where entities can have parent-child
 * relationships, forming hierarchies such as category trees, organizational charts, or geographic regions. This
 * interface marks constraints that interact with these hierarchical structures.
 *
 * **Hierarchy Data Model**
 *
 * evitaDB entities can form hierarchies in two ways:
 * 1. **Self-hierarchies**: entities can have a parent entity of the same type (e.g., categories form a category tree)
 * 2. **Referenced hierarchies**: entities can reference hierarchical entities of a different type (e.g., products
 *    reference categories, which form a hierarchy)
 *
 * Each hierarchy is represented as a tree with:
 * - **Root nodes**: entities with no parent
 * - **Parent-child relationships**: each entity can have at most one parent
 * - **Levels/depth**: distance from root
 * - **Subtrees**: all descendants of a node
 * - **Paths**: chains from root to a specific node
 *
 * **Property Type System**
 *
 * This interface represents the `HIERARCHY` property type in evitaDB's constraint classification system. Along with
 * other property-type-defining interfaces ({@link EntityConstraint}, {@link AttributeConstraint},
 * {@link PriceConstraint}, {@link AssociatedDataConstraint}, {@link ReferenceConstraint}, {@link FacetConstraint},
 * {@link GenericConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Tree-specific query patterns (subtree filtering, ancestor/descendant queries)
 *
 * **Constraint Domains**
 *
 * Hierarchy constraints are used primarily in the `ENTITY` domain, operating on hierarchical relationships defined
 * in the entity schema. They can apply to:
 * - Self-hierarchical entities (entities that reference themselves)
 * - Referenced hierarchical entities (entities that reference other hierarchical entity types)
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `HierarchyWithin` - filters by subtree inclusion, `HierarchyWithinRoot` - filters by subtree
 *   from root, `HierarchyDirectRelation` - filters by direct parent-child relationship, `HierarchyExcluding` -
 *   excludes specific subtrees, `HierarchyExcludingRoot` - excludes root subtrees, `HierarchyHaving` - filters
 *   by referenced hierarchy properties
 * - **Requirements**: `HierarchyOfSelf` - computes hierarchy statistics for self-hierarchies, `HierarchyOfReference` -
 *   computes hierarchy statistics for referenced hierarchies, `HierarchyContent` - fetches parent chain data,
 *   `HierarchyParents` - fetches parent entities, `HierarchySiblings` - fetches sibling entities,
 *   `HierarchyChildren` - fetches child entities, `HierarchyStatistics` - computes cardinality per hierarchy node
 *
 * **Hierarchy Query Patterns**
 *
 * Hierarchy constraints enable several common tree query patterns:
 * - **Subtree filtering**: "Find all products in category X or its descendants"
 * - **Level filtering**: "Find categories at depth 2"
 * - **Ancestor path**: "Get the full path from root to this category"
 * - **Siblings**: "Find all categories at the same level as X"
 * - **Statistics**: "Count how many products are in each category (including subcategories)"
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `RequireConstraint`) that defines the
 * constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Filter products by category subtree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", 5) // category 5 and all descendants
 *     )
 * )
 *
 * // Get category tree with product counts
 * query(
 *     collection("Product"),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("name")),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 *
 * // Fetch parent hierarchy chain
 * query(
 *     collection("Category"),
 *     filterBy(
 *         entityPrimaryKeyInSet(42)
 *     ),
 *     require(
 *         entityFetch(
 *             hierarchyContent(
 *                 entityFetch(attributeContent("name", "code"))
 *             )
 *         )
 *     )
 * )
 *
 * // Filter by direct parent only
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             5,
 *             directRelation()
 *         )
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Hierarchies are a first-class concept in evitaDB because:
 * 1. **E-commerce prevalence**: category trees are fundamental to e-commerce catalogs
 * 2. **Performance**: evitaDB maintains specialized indexes for efficient subtree queries and statistics computation
 * 3. **Complex queries**: tree traversal logic is complex and error-prone when implemented as generic attribute queries
 * 4. **API clarity**: external APIs benefit from explicit hierarchy-specific types and constraints
 *
 * **Relationship with ReferenceConstraint**
 *
 * While {@link ReferenceConstraint} handles general entity references, `HierarchyConstraint` specifically targets
 * hierarchical references. A reference becomes hierarchical when the referenced entity type is defined as
 * hierarchical in the schema. Hierarchy constraints provide tree-aware operations that are not available for
 * non-hierarchical references.
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `HierarchyConstraint` are
 * registered with the `HIERARCHY` property type in the constraint descriptor registry.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe, as they are frequently shared across concurrent
 * query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint or RequireConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see ReferenceConstraint for general (non-hierarchical) reference constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface HierarchyConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
