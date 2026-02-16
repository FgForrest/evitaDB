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
 * Marker interface for constraints that operate on entity price lists. This interface is part of evitaDB's property
 * type system, which categorizes constraints based on the type of data they target. `PriceConstraint` identifies
 * constraints that filter by prices, order by prices, compute price statistics, or fetch price data into query
 * results.
 *
 * **Purpose**
 *
 * Prices are a first-class concept in evitaDB due to e-commerce requirements for complex pricing logic. An entity
 * can have multiple prices, each with:
 * - **Price amount**: With and without tax
 * - **Currency**: ISO currency code (e.g., USD, EUR, CZK)
 * - **Price list**: Named price list identifier (e.g., "basic", "vip", "wholesale")
 * - **Validity**: Optional temporal validity (from/to timestamps)
 * - **Inner record ID**: Distinguishes multiple prices with the same price list and currency
 *
 * `PriceConstraint` marks constraints that can query, manipulate, or retrieve entity prices, enabling sophisticated
 * pricing scenarios: multi-currency catalogs, price list hierarchies, time-based pricing, and price range filtering.
 *
 * **Price Data Model**
 *
 * evitaDB's price model supports e-commerce scenarios such as:
 * - **Multi-currency**: Each entity can have prices in multiple currencies
 * - **Multi-price-list**: Prices can belong to different lists (customer segment pricing, promotional pricing)
 * - **Temporal validity**: Prices can have start/end timestamps for scheduled pricing
 * - **Tax handling**: Prices store both with-tax and without-tax amounts
 * - **Price selection**: Queries select a single "selling price" per entity based on currency, price lists, and validity
 *
 * Example: A product might have:
 * - EUR 99.99 in "basic" price list, valid from 2025-01-01 to 2025-06-30
 * - EUR 89.99 in "vip" price list, valid from 2025-01-01 to 2025-06-30
 * - USD 109.99 in "basic" price list, valid from 2025-01-01 to 2025-12-31
 *
 * **Property Type System**
 *
 * This interface represents the `PRICE` property type in evitaDB's constraint classification system. Along with other
 * property-type-defining interfaces ({@link GenericConstraint}, {@link EntityConstraint}, {@link AttributeConstraint},
 * {@link AssociatedDataConstraint}, {@link ReferenceConstraint}, {@link HierarchyConstraint}, {@link FacetConstraint}),
 * it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Runtime dispatch to price-specific query execution logic with specialized indexes
 *
 * **Constraint Domains**
 *
 * Price constraints are used exclusively in the `ENTITY` domain. Prices are properties of entities, not references
 * or other data types. There is no concept of "reference prices" or "hierarchy prices" — only entities have prices.
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `PriceBetween` (filter by price range), `PriceInCurrency` (filter by currency), `PriceInPriceLists`
 *   (select price lists for price resolution), `PriceValidIn` (filter by temporal validity)
 * - **Ordering**: `PriceNatural` (order by selling price ascending/descending), `PriceDiscount` (order by price
 *   discount percentage)
 * - **Requirements**: `PriceContent` (fetch prices into results), `PriceHistogram` (compute price distribution
 *   histogram), `PriceType` (specify price tax handling), `DefaultAccompanyingPriceLists` (configure default price
 *   list fallback)
 *
 * **Price Query Patterns**
 *
 * Price constraints enable several common e-commerce patterns:
 * - **Budget filtering**: "Show products between $100 and $500"
 * - **Currency selection**: "Show prices in EUR"
 * - **Price list selection**: "Use VIP pricing, fallback to basic pricing"
 * - **Temporal pricing**: "Show prices valid on 2025-12-25"
 * - **Price distribution**: "Show histogram of product prices in 20 buckets"
 * - **Price-based ordering**: "Sort products by price ascending"
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
 * // Filter by price range
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             priceInCurrency(Currency.EUR),
 *             priceInPriceLists("basic"),
 *             priceBetween(100, 1000)
 *         )
 *     )
 * )
 *
 * // Filter by temporal validity
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             priceInCurrency(Currency.USD),
 *             priceInPriceLists("basic", "vip"),
 *             priceValidIn(OffsetDateTime.now())
 *         )
 *     )
 * )
 *
 * // Order by price
 * query(
 *     collection("Product"),
 *     filterBy(
 *         priceInCurrency(Currency.EUR),
 *         priceInPriceLists("basic")
 *     ),
 *     orderBy(
 *         priceNatural(ASC)  // ascending price order
 *     )
 * )
 *
 * // Fetch prices and compute histogram
 * query(
 *     collection("Product"),
 *     filterBy(
 *         priceInCurrency(Currency.EUR),
 *         priceInPriceLists("basic")
 *     ),
 *     require(
 *         priceContentRespectingFilter(),  // fetch prices matching filter
 *         priceHistogram(20)  // 20 histogram buckets
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Prices are a first-class property type (rather than being modeled as attributes) because:
 * 1. **Complex semantics**: Multi-currency, multi-price-list, temporal validity, and tax handling require specialized
 *    logic beyond simple key-value attributes
 * 2. **Specialized indexes**: evitaDB maintains price-specific indexes for efficient range filtering, price list
 *    selection, and currency conversion
 * 3. **E-commerce ubiquity**: Pricing is fundamental to e-commerce catalogs and deserves dedicated query language
 *    support
 * 4. **Performance**: Price histograms and price-based filtering are performance-critical operations that benefit
 *    from specialized data structures
 * 5. **API clarity**: External APIs need explicit price types distinct from generic attributes
 *
 * **Price Selection Logic**
 *
 * When a query filters or orders by price, evitaDB selects a single "selling price" per entity based on:
 * 1. **Currency**: Matches `priceInCurrency` constraint
 * 2. **Price lists**: Searches price lists in order specified by `priceInPriceLists` constraint
 * 3. **Validity**: Matches `priceValidIn` constraint (if specified)
 * 4. **Inner record ID**: Uses lowest inner record ID if multiple prices match
 *
 * Only entities with a matching selling price appear in results when price filters are used.
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `PriceConstraint` are
 * registered with the `PRICE` property type in the constraint descriptor registry.
 *
 * At query execution time, evitaDB validates:
 * - The entity type has prices enabled in the schema
 * - Price-related indexes are built (indexed prices are required for filtering and ordering)
 * - Currency is specified via `priceInCurrency` when filtering by price
 * - At least one price list is specified via `priceInPriceLists`
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint, OrderConstraint, or RequireConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface PriceConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
