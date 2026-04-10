/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
 * Marker interface for constraints that filter entities in an evitaDB query. Filter constraints define the logical
 * conditions that determine which entities are included in the query results, analogous to the `WHERE` clause in SQL.
 *
 * Filter constraints form a composable tree structure where container constraints (such as `and`, `or`, `not`)
 * can combine multiple leaf constraints (such as `equals`, `greaterThan`, `between`) to express complex boolean
 * logic. All filtering constraints implement this interface to allow type-safe construction and validation of
 * filter expressions.
 *
 * **Design Context:**
 *
 * This interface extends {@link TypeDefiningConstraint} which provides type identity for the constraint system.
 * Filter constraints are one of the four primary constraint categories in evitaDB's query language (EvitaQL),
 * alongside {@link HeadConstraint}, {@link OrderConstraint}, and {@link RequireConstraint}.
 *
 * Filter constraints are used in the {@link io.evitadb.api.query.filter.FilterBy} container which wraps all
 * filtering conditions in a query. The `FilterBy` container combines its children using logical conjunction (AND)
 * by default — explicit `or` and `not` constraints must be used for disjunction and negation.
 *
 * **Common Implementations:**
 *
 * - **Comparison constraints**: `equals`, `greaterThan`, `lessThan`, `between`, `inSet`
 * - **String matching**: `startsWith`, `endsWith`, `contains`
 * - **Logical operators**: `and`, `or`, `not`, `userFilter`
 * - **Entity properties**: `entityPrimaryKeyInSet`, `entityLocaleEquals`, `entityScope`
 * - **Hierarchy queries**: `hierarchyWithin`, `hierarchyWithinRoot`, `hierarchyExcluding`
 * - **Reference/facet queries**: `referenceHaving`, `facetHaving`
 * - **Price constraints**: `priceInCurrency`, `priceInPriceLists`, `priceBetween`, `priceValidIn`
 *
 * **Usage Example:**
 *
 * ```java
 * // Using filter constraints via QueryConstraints factory methods
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             equals("visible", true),
 *             or(
 *                 equals("code", "PRODUCT-1"),
 *                 startsWith("name", "Smart")
 *             ),
 *             priceBetween(100, 1000)
 *         )
 *     )
 * );
 * ```
 *
 * **Thread Safety:**
 *
 * All implementations of this interface must be immutable and therefore thread-safe. Filter constraints are
 * designed to be safely shared across multiple queries and threads without synchronization.
 *
 * @see io.evitadb.api.query.filter.FilterBy
 * @see io.evitadb.api.query.QueryConstraints
 * @see TypeDefiningConstraint
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface FilterConstraint extends TypeDefiningConstraint<FilterConstraint> {
	/**
	 * Empty array constant to avoid repeated allocation.
	 */
	FilterConstraint[] EMPTY_ARRAY = new FilterConstraint[0];

}
