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
 * Marker interface for constraints that operate on core entity properties: primary key, entity type, locale, and
 * scope. This interface is part of evitaDB's property type system, which categorizes constraints based on the type
 * of data they target. `EntityConstraint` identifies constraints that filter by, order by, or manipulate built-in
 * entity metadata rather than user-defined attributes or other custom data.
 *
 * **Purpose**
 *
 * Every entity in evitaDB has a set of built-in properties that define its identity and context:
 * - **Primary key**: Unique integer identifier within the entity collection
 * - **Entity type**: The collection this entity belongs to (e.g., "Product", "Category", "Brand")
 * - **Locale**: The locale context for localized data (optional, can be `null` for non-localized queries)
 * - **Scope**: The entity scope indicating visibility state (e.g., `LIVE` for published entities, `ARCHIVED` for
 *   historical entities)
 *
 * `EntityConstraint` marks constraints that operate on these core properties, distinguishing them from constraints
 * that operate on user-defined attributes ({@link AttributeConstraint}), prices ({@link PriceConstraint}), references
 * ({@link ReferenceConstraint}), or other data types.
 *
 * **Property Type System**
 *
 * This interface represents the `ENTITY` property type in evitaDB's constraint classification system. Along with other
 * property-type-defining interfaces ({@link GenericConstraint}, {@link AttributeConstraint},
 * {@link AssociatedDataConstraint}, {@link PriceConstraint}, {@link ReferenceConstraint}, {@link HierarchyConstraint},
 * {@link FacetConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Runtime dispatch to entity-property-specific query execution logic
 *
 * **Constraint Domains**
 *
 * Entity constraints are primarily used in the `ENTITY` domain, operating on properties of queried entities. They can
 * also appear in `REFERENCE` context when filtering or ordering by properties of referenced entities (e.g., filtering
 * products by the locale of their brand entity).
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `EntityPrimaryKeyInSet` (filter by primary keys), `EntityLocaleEquals` (filter by locale),
 *   `EntityScope` (filter by scope — live, archived, etc.), `EntityHaving` (filter by referenced entity properties)
 * - **Ordering**: `EntityPrimaryKeyNatural` (order by primary key), `EntityPrimaryKeyExact` (explicit primary key
 *   order), `EntityPrimaryKeyInFilter` (order by primary key appearance in filter), `EntityProperty` (order by
 *   referenced entity properties), `EntityGroupProperty` (order by group entity properties)
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `OrderConstraint`) that defines the
 * constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Filter by primary keys
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(1, 2, 3, 5, 8)
 *     )
 * )
 *
 * // Filter by locale
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityLocaleEquals(Locale.ENGLISH)
 *     )
 * )
 *
 * // Filter by scope
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityScope(Scope.LIVE)  // only published entities
 *     )
 * )
 *
 * // Order by primary key
 * query(
 *     collection("Product"),
 *     orderBy(
 *         entityPrimaryKeyNatural(ASC)
 *     )
 * )
 *
 * // Explicit primary key order
 * query(
 *     collection("Product"),
 *     orderBy(
 *         entityPrimaryKeyExact(10, 5, 3, 1)  // results in this exact order
 *     )
 * )
 *
 * // Filter products by referenced entity properties
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "brand",
 *             entityHaving(
 *                 attributeEquals("country", "USA")  // brand entity attribute
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Core entity properties are separated from user-defined attributes because:
 * 1. **Universal presence**: These properties exist on every entity regardless of schema configuration
 * 2. **Identity semantics**: Primary key and entity type define entity identity and are handled specially by indexes
 * 3. **Context management**: Locale and scope affect query execution context and data visibility
 * 4. **API consistency**: External APIs need distinct types for built-in properties vs. custom attributes
 * 5. **Performance**: Primary key operations use specialized indexes optimized for identity lookups
 *
 * **Relationship with AttributeConstraint**
 *
 * The distinction between `EntityConstraint` and {@link AttributeConstraint} is:
 * - `EntityConstraint`: Operates on built-in, always-present entity properties
 * - {@link AttributeConstraint}: Operates on user-defined, schema-configured attributes
 *
 * For example:
 * - `entityPrimaryKeyInSet(1, 2, 3)` is an `EntityConstraint`
 * - `attributeEquals("name", "iPhone")` is an {@link AttributeConstraint}
 *
 * While both filter entities, they target different data and use different indexes.
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `EntityConstraint` are
 * registered with the `ENTITY` property type in the constraint descriptor registry.
 *
 * Unlike attribute constraints, entity constraints require no schema validation — the properties they target always
 * exist.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint or OrderConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see AttributeConstraint for user-defined attribute constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface EntityConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
