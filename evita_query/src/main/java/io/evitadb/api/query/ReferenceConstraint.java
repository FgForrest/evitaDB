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
 * Marker interface for constraints that operate on entity references. This interface is part of evitaDB's property
 * type system, which categorizes constraints based on the type of data they target.
 *
 * **Purpose**
 *
 * `ReferenceConstraint` identifies constraints that can query, filter, order, or require data related to references
 * between entities. References represent relationships such as "product references brand", "product references
 * category", or "order references customer". evitaDB supports rich reference semantics including reference attributes,
 * group entities, and cardinality constraints.
 *
 * **Reference Data Model**
 *
 * An entity reference in evitaDB consists of:
 * - **Reference name**: the type of relationship (e.g., "brand", "category", "relatedProducts")
 * - **Referenced entity**: primary key and optionally the type of the target entity
 * - **Reference attributes**: key-value pairs stored on the reference itself (not on the referenced entity)
 * - **Group entity**: optional grouping entity (e.g., products reference parameter values grouped by parameter groups)
 * - **Faceted flag**: whether this reference should be treated as a facet for navigation
 *
 * Example: A product may have:
 * - Reference to brand entity #42 (name: "brand", referencedId: 42)
 * - Reference to category entity #10 with attribute priority=1 (stored on the reference)
 * - Reference to size entity #5, grouped by sizeGroup entity #100
 * - Reference to tag entities #1, #2, #3 (multiple references with the same name)
 *
 * **Property Type System**
 *
 * This interface represents the `REFERENCE` property type in evitaDB's constraint classification system. Along with
 * other property-type-defining interfaces ({@link EntityConstraint}, {@link AttributeConstraint},
 * {@link PriceConstraint}, {@link AssociatedDataConstraint}, {@link HierarchyConstraint}, {@link FacetConstraint},
 * {@link GenericConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Relationship-based querying patterns
 *
 * **Constraint Domains**
 *
 * Reference constraints are used primarily in the `ENTITY` domain, operating on references owned by entities. They
 * enable filtering entities based on their references, ordering entities by reference properties, and fetching
 * referenced entity data into query results.
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `ReferenceHaving` - filters entities by reference existence or properties (similar to SQL EXISTS
 *   with JOIN)
 * - **Ordering**: `ReferenceProperty` - orders entities by reference attributes, `PickFirstByEntityProperty` -
 *   orders references by referenced entity properties
 * - **Requirements**: `ReferenceContent` - fetches referenced entities into results, `ReferenceAttributeContent` -
 *   fetches reference attributes
 *
 * **Reference Query Patterns**
 *
 * Reference constraints enable several common relational query patterns:
 * - **Existence check**: "Find products that have any brand reference"
 * - **Target filtering**: "Find products that reference brand #42"
 * - **Attribute filtering**: "Find products with priority=1 on their category reference"
 * - **Referenced entity filtering**: "Find products whose brand has country='USA'"
 * - **Reference fetching**: "Load brand entities with each product"
 * - **Ordering by reference**: "Order products by their brand's name"
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `OrderConstraint`, `RequireConstraint`)
 * that defines the constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Filter by reference existence
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving("brand") // has any brand reference
 *     )
 * )
 *
 * // Filter by referenced entity primary key
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "brand",
 *             entityPrimaryKeyInSet(42, 100)
 *         )
 *     )
 * )
 *
 * // Filter by reference attribute
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "category",
 *             attributeEquals("priority", 1) // attribute on the reference, not on category entity
 *         )
 *     )
 * )
 *
 * // Filter by referenced entity properties
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "brand",
 *             entityHaving(
 *                 attributeEquals("country", "USA") // attribute on the brand entity
 *             )
 *         )
 *     )
 * )
 *
 * // Order by reference attribute
 * query(
 *     collection("Product"),
 *     orderBy(
 *         referenceProperty(
 *             "category",
 *             attributeNatural("priority", ASC)
 *         )
 *     )
 * )
 *
 * // Fetch referenced entities
 * query(
 *     collection("Product"),
 *     require(
 *         entityFetch(
 *             referenceContent(
 *                 "brand",
 *                 entityFetch(attributeContent("name", "logo"))
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * References are a first-class concept in evitaDB because:
 * 1. **Relational queries**: e-commerce catalogs require filtering and ordering by relationships (products by brand,
 *    category, store availability)
 * 2. **Denormalization**: evitaDB allows storing reference-specific attributes (e.g., priority, validity dates) on
 *    references themselves, avoiding entity duplication
 * 3. **Performance**: specialized indexes enable efficient reference filtering without expensive joins
 * 4. **API clarity**: external APIs benefit from explicit reference-specific types and constraints
 *
 * **Reference vs. Facet vs. Hierarchy**
 *
 * References can have additional semantics:
 * - {@link ReferenceConstraint}: general entity relationships
 * - {@link FacetConstraint}: references marked as faceted, with automatic statistics computation
 * - {@link HierarchyConstraint}: references to hierarchical entities, with tree traversal operations
 *
 * A single reference can be both faceted and hierarchical (e.g., category references where categories form a tree
 * and are used for faceted navigation).
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `ReferenceConstraint` are
 * registered with the `REFERENCE` property type in the constraint descriptor registry.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe, as they are frequently shared across concurrent
 * query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint, OrderConstraint, or RequireConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see FacetConstraint for faceted reference constraints
 * @see HierarchyConstraint for hierarchical reference constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ReferenceConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
