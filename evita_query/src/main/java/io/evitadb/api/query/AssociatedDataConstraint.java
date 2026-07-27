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
 * Marker interface for constraints that operate on entity associated data — complex structured data blobs stored with
 * entities but not indexed for filtering or sorting. This interface is part of evitaDB's property type system, which
 * categorizes constraints based on the type of data they target. `AssociatedDataConstraint` identifies constraints
 * that fetch associated data into query results.
 *
 * **Purpose**
 *
 * Associated data represents large, complex structured data (JSON objects, complex POJOs) that needs to be stored
 * with entities but does not require indexing for query operations. Unlike attributes, which are designed for
 * filtering and sorting, associated data is purely for storage and retrieval — it is fetched alongside entities when
 * explicitly requested but cannot be used in filter or order constraints.
 *
 * Typical use cases for associated data:
 * - **Rich content**: Product descriptions, specifications, marketing copy (large text with HTML/markdown)
 * - **Configuration**: Complex configuration objects (JSON structures with nested data)
 * - **Metadata**: Editorial metadata, SEO data, workflow states (structured but not queryable)
 * - **Media**: References to images, videos, documents (URLs, metadata, processing state)
 *
 * **Associated Data Model**
 *
 * Associated data in evitaDB has these characteristics:
 * - **Keys**: Named identifiers (similar to attribute names)
 * - **Values**: Arbitrary serializable objects (JSON, POJOs, complex structures)
 * - **Localization**: Can be localized (different values per locale) or global
 * - **No indexing**: Not indexed for filtering or sorting — purely for retrieval
 * - **Schema definition**: Declared in entity schema with type information but no index configuration
 * - **Large size**: Can store large objects (megabytes) without impacting query performance
 *
 * **Property Type System**
 *
 * This interface represents the `ASSOCIATED_DATA` property type in evitaDB's constraint classification system. Along
 * with other property-type-defining interfaces ({@link GenericConstraint}, {@link EntityConstraint},
 * {@link AttributeConstraint}, {@link PriceConstraint}, {@link ReferenceConstraint}, {@link HierarchyConstraint},
 * {@link FacetConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Runtime dispatch to associated-data-specific data fetching logic
 *
 * **Constraint Domains**
 *
 * Associated data constraints are used exclusively in the `ENTITY` domain. Associated data is a property of entities,
 * not references or other data types. References cannot have associated data (they have reference attributes instead).
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Requirements**: `AssociatedDataContent` (fetch associated data into results with specified keys or all)
 *
 * Unlike other property types, associated data has **no filter or order constraints**. It is purely a requirement
 * property type used to control what data is fetched from the database.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (always `RequireConstraint` for associated data, since filtering
 * and ordering are not supported).
 *
 * **Example Usage**
 *
 * ```java
 * // Fetch specific associated data
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(1, 2, 3)
 *     ),
 *     require(
 *         entityFetch(
 *             associatedDataContent("description", "specifications")
 *         )
 *     )
 * )
 *
 * // Fetch all associated data
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("visible", true)
 *     ),
 *     require(
 *         entityFetch(
 *             associatedDataContentAll()
 *         )
 *     )
 * )
 *
 * // Fetch localized associated data (requires entityLocaleEquals)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityLocaleEquals(Locale.ENGLISH),
 *             attributeEquals("visible", true)
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             associatedDataContent("description", "marketingCopy")
 *         )
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Associated data exists as a separate property type from attributes because:
 * 1. **Storage vs. indexing**: Not all entity data needs to be indexed for querying — storing large blobs in indexes
 *    would waste memory and slow down queries
 * 2. **Performance**: Separating storage-only data from indexed attributes allows evitaDB to optimize query
 *    performance by only loading associated data when explicitly requested
 * 3. **Size limits**: Attributes are optimized for small, primitive values; associated data can store megabytes of
 *    structured content
 * 4. **Type complexity**: Associated data can store arbitrary complex objects (nested structures, arrays of objects)
 *    that don't fit attribute semantics
 * 5. **API clarity**: External APIs benefit from distinguishing queryable attributes from non-queryable complex data
 *
 * **Relationship with AttributeConstraint**
 *
 * The distinction between `AssociatedDataConstraint` and {@link AttributeConstraint} is:
 * - {@link AttributeConstraint}: Indexed key-value pairs used for filtering, sorting, and fetching
 * - `AssociatedDataConstraint`: Non-indexed complex data used only for fetching
 *
 * When to use attributes vs. associated data:
 * - **Use attributes** for: Small values (strings, numbers, dates), data used in filters or sorts, data with simple types
 * - **Use associated data** for: Large values (descriptions, specs), complex structures (nested JSON), data never
 *   used in queries
 *
 * **Localization**
 *
 * Associated data can be localized (different values per locale). When fetching localized associated data:
 * 1. The query must include `entityLocaleEquals` constraint to specify the locale
 * 2. evitaDB returns associated data for the specified locale
 * 3. If no value exists for the requested locale, no associated data is returned (no fallback to global values)
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `AssociatedDataConstraint`
 * are registered with the `ASSOCIATED_DATA` property type in the constraint descriptor registry.
 *
 * At query execution time, evitaDB validates:
 * - The entity type has the requested associated data keys defined in the schema
 * - For localized associated data, a locale is specified via `entityLocaleEquals`
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions.
 *
 * @param <T> the constraint type classification (RequireConstraint — associated data has no filter or order constraints)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see AttributeConstraint for indexed key-value attribute constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AssociatedDataConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
