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
 * Marker interface for constraints that provide structural, logical, or control-flow operations rather than
 * operating on specific entity data properties. This interface is part of evitaDB's property type system, which
 * categorizes constraints based on the type of data they target.
 *
 * **Purpose**
 *
 * `GenericConstraint` identifies constraints that serve as query structure elements: logical operators, container
 * wrappers, pagination controls, and other meta-operations that organize or control how other constraints are
 * evaluated. Unlike property-specific constraints ({@link AttributeConstraint}, {@link PriceConstraint}, etc.),
 * generic constraints do not directly query entity attributes, prices, references, or other data properties.
 *
 * **Characteristics of Generic Constraints**
 *
 * Generic constraints:
 * - **Do not target entity data**: they don't filter by attributes, prices, references, or other entity properties
 * - **Provide structure**: they organize other constraints (containers, logical operators)
 * - **Control execution**: they affect query behavior (pagination, localization, scoping)
 * - **Coordinate constraints**: they combine or modify other constraints' behavior
 *
 * **Property Type System**
 *
 * This interface represents the `GENERIC` property type in evitaDB's constraint classification system. Along with
 * other property-type-defining interfaces ({@link EntityConstraint}, {@link AttributeConstraint},
 * {@link PriceConstraint}, {@link AssociatedDataConstraint}, {@link ReferenceConstraint},
 * {@link HierarchyConstraint}, {@link FacetConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Clear separation of data operations vs. structural operations
 *
 * **Constraint Domains**
 *
 * Generic constraints can be used in multiple domains depending on their specific purpose:
 * - `ENTITY`, `REFERENCE`, `FACET`, `INLINE_REFERENCE`: for logical operators and containers
 * - `HEAD`, `FILTER`, `ORDER`, `REQUIRE`: for top-level query structure elements
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Logical operators**: `And`, `Or`, `Not` - combine constraints with boolean logic
 * - **Top-level containers**: `FilterBy`, `OrderBy`, `Require` - organize query sections
 * - **Scoping containers**: `FilterInScope`, `OrderInScope`, `RequireInScope`, `EntityScope` - apply constraints in
 *   specific contexts
 * - **User filter**: `UserFilter` - segregates user-initiated filters for facet computation
 * - **Pagination**: `Page`, `Strip` - control result set pagination
 * - **Localization**: `DataInLocales` - specify locales for localized data
 * - **Segmentation**: `Segment`, `Segments`, `SegmentLimit` - partition and limit results by segments
 * - **Metadata**: `Label` - add semantic labels to queries
 * - **Diagnostics**: `QueryTelemetry` - enable query performance monitoring
 * - **Spacing**: `Spacing`, `SpacingGap` - control visual spacing in result presentation
 * - **Random ordering**: `Random` - randomize result order
 * - **Entity fetch**: `EntityFetch` - top-level projection container (though it contains property-specific
 *   constraints, it is itself generic)
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
 * // Logical operators
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("available", true),
 *             or(
 *                 attributeEquals("brand", "Nike"),
 *                 attributeEquals("brand", "Adidas")
 *             )
 *         )
 *     )
 * )
 *
 * // Pagination
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("category", "Shoes")
 *     ),
 *     require(
 *         page(1, 20) // page 1, 20 items per page
 *     )
 * )
 *
 * // Localization
 * query(
 *     collection("Product"),
 *     require(
 *         dataInLocales(Locale.ENGLISH, Locale.GERMAN)
 *     )
 * )
 *
 * // User filter for facet computation
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             facetHaving("brand", entityPrimaryKeyInSet(1, 2, 3))
 *         )
 *     ),
 *     require(
 *         facetSummary()
 *     )
 * )
 *
 * // Query labeling for debugging
 * query(
 *     collection("Product"),
 *     label("homepage-featured-products"),
 *     filterBy(
 *         attributeEquals("featured", true)
 *     )
 * )
 *
 * // Scoped constraints
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityScope(Scope.LIVE),
 *         filterInScope(
 *             Scope.ARCHIVED,
 *             attributeEquals("migrated", true)
 *         )
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Generic constraints exist because:
 * 1. **Query structure**: queries need logical operators and containers to organize complex filter/order/require
 *    expressions
 * 2. **Execution control**: queries need pagination, localization, and scoping directives that are orthogonal to
 *    data properties
 * 3. **API consistency**: external API generators benefit from explicitly marking structural vs. data-targeting
 *    constraints
 * 4. **Type safety**: the property type system ensures constraints are used in appropriate contexts and prevents
 *    mixing structural operations with data operations inappropriately
 *
 * **Distinction from Property-Specific Constraints**
 *
 * The key distinction is:
 * - **Property-specific constraints** ({@link AttributeConstraint}, {@link PriceConstraint}, etc.): target entity
 *   data and are resolved by accessing entity properties
 * - **Generic constraints**: provide control flow, structure, or metadata without directly accessing entity data
 *
 * For example:
 * - `attributeEquals("name", "iPhone")` is an {@link AttributeConstraint} - it queries entity attributes
 * - `and(...)` is a `GenericConstraint` - it combines other constraints without querying entity data
 * - `page(1, 20)` is a `GenericConstraint` - it controls result pagination without querying entity data
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `GenericConstraint` are
 * registered with the `GENERIC` property type in the constraint descriptor registry.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe, as they are frequently shared across concurrent
 * query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint, OrderConstraint, RequireConstraint, or
 *           HeadConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface GenericConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
