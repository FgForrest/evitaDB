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
 * Marker interface defining the first dimension of evitaDB's constraint classification system: **constraint purpose**.
 * This interface is the parent of four type-defining interfaces that categorize constraints by their role in a query.
 * Every constraint in evitaDB implements exactly one type-defining interface, determining whether it filters entities,
 * orders results, specifies data requirements, or provides query metadata.
 *
 * **Purpose in the Constraint System**
 *
 * evitaDB's query language organizes constraints into a two-dimensional classification:
 * 1. **Type (Purpose)** — defined by this interface and its children — **what the constraint does**
 * 2. **Property Type** — defined by {@link PropertyTypeDefiningConstraint} — **what data the constraint operates on**
 *
 * The type dimension answers: "Is this a filter, an ordering rule, a data requirement, or query metadata?"
 *
 * **The Four Constraint Types**
 *
 * This interface has exactly four direct descendants, each representing a distinct query purpose:
 *
 * **1. {@link io.evitadb.api.query.HeadConstraint}** — Query Metadata and Collection Targeting
 *
 * Head constraints specify which entity collection to query and attach metadata labels for debugging or monitoring.
 * Every query targeting a specific entity collection must include a `collection` head constraint.
 *
 * Examples: `collection("Product")`, `label("source", "homepage")`
 *
 * **2. {@link io.evitadb.api.query.FilterConstraint}** — Entity Filtering (WHERE clause)
 *
 * Filter constraints define logical conditions that determine which entities match the query. They form boolean
 * expressions using comparison operators, logical combinators, and specialized filters for prices, hierarchies,
 * references, and facets.
 *
 * Examples: `attributeEquals("visible", true)`, `priceBetween(100, 1000)`, `hierarchyWithin("categories", 5)`,
 * `and(...)`, `or(...)`
 *
 * **3. {@link io.evitadb.api.query.OrderConstraint}** — Result Ordering (ORDER BY clause)
 *
 * Order constraints specify how matched entities should be sorted in the result set. evitaDB uses pre-built sort
 * indexes for efficient ordering, so queries can only sort by attributes and compounds marked as sortable in the
 * entity schema.
 *
 * Examples: `attributeNatural("name", ASC)`, `priceNatural(ASC)`, `random()`, `referenceProperty(...)`
 *
 * **4. {@link io.evitadb.api.query.RequireConstraint}** — Data Requirements and Extra Computations
 *
 * Require constraints control what data is fetched for matched entities, configure pagination, and request extra
 * computations (histograms, statistics, facet summaries). They never affect which entities match or their order —
 * only what data is returned and what additional processing occurs.
 *
 * Examples: `entityFetch(...)`, `page(1, 20)`, `facetSummary()`, `priceHistogram(20)`, `hierarchyOfReference(...)`
 *
 * **Query Structure**
 *
 * EvitaQL queries organize these constraint types into a structured format:
 *
 * ```
 * query(
 *     collection('Product'),              // HeadConstraint
 *     filterBy(...),                      // Container for FilterConstraints
 *     orderBy(...),                       // Container for OrderConstraints
 *     require(...)                        // Container for RequireConstraints
 * )
 * ```
 *
 * Each section is optional except for the collection specification (or it's implicitly provided by the query context).
 *
 * **Type Safety and Validation**
 *
 * The type system enforces correct constraint usage at compile time:
 * - A `FilterConstraint` cannot appear in an `orderBy()` container
 * - An `OrderConstraint` cannot appear in a `filterBy()` container
 * - Container constraints only accept children of matching types
 *
 * This prevents nonsensical queries like `filterBy(attributeNatural("name"))` or `orderBy(attributeEquals("x", 1))`.
 *
 * **Relationship with PropertyTypeDefiningConstraint**
 *
 * Type-defining and property-type-defining interfaces work together to fully categorize constraints:
 * - **TypeDefiningConstraint** (this interface) → purpose: filter, order, require, head
 * - **{@link PropertyTypeDefiningConstraint}** → data target: attribute, price, reference, entity, etc.
 *
 * Example: `attributeEquals` implements both `FilterConstraint` (type: filtering) and `AttributeConstraint`
 * (property type: attributes). This dual classification enables:
 * - Compile-time type safety (can only use in `filterBy()`)
 * - Runtime dispatch (query engine knows to filter by attributes)
 * - API schema generation (groups constraints by both dimensions)
 *
 * **Design Pattern: Marker Interfaces**
 *
 * This is a marker interface — it declares no methods beyond those inherited from {@link Constraint}. Its sole
 * purpose is type identification. Constraints implement type-defining interfaces to declare their category, and
 * the type system uses these markers for validation and dispatch.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends Constraint<T>` enables self-referential typing:
 * ```java
 * public interface FilterConstraint extends TypeDefiningConstraint<FilterConstraint> { }
 * ```
 * This allows methods like {@link Constraint#cloneWithArguments(java.io.Serializable[])} to return the specific
 * constraint type rather than a generic `Constraint`, maintaining type information through transformations.
 *
 * **Implementation Note**
 *
 * This interface should **only be extended by the four type-defining interfaces** listed above. Concrete constraint
 * classes should implement one of those four, not this interface directly. The constraint system is closed to new
 * constraint types — all queries must fit into the head/filter/order/require taxonomy.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions.
 *
 * @param <T> the specific constraint type, enabling self-referential typing for type-safe constraint operations
 * @see io.evitadb.api.query.HeadConstraint
 * @see io.evitadb.api.query.FilterConstraint
 * @see io.evitadb.api.query.OrderConstraint
 * @see io.evitadb.api.query.RequireConstraint
 * @see PropertyTypeDefiningConstraint
 * @see Constraint
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface TypeDefiningConstraint<T extends Constraint<T>> extends Constraint<T> {
}
