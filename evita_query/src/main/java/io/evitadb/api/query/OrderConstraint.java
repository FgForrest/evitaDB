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
 * Marker interface for constraints that define the ordering of entities in an evitaDB query result set. Order
 * constraints specify how entities should be sorted when returned from the database, analogous to the `ORDER BY`
 * clause in SQL.
 *
 * Unlike traditional SQL databases, evitaDB relies on pre-built sort indexes for efficient ordering. This design
 * choice sacrifices ad-hoc sorting flexibility for predictable query performance. Queries can only sort by attributes
 * and compounds that have been explicitly marked as sortable in the entity schema.
 *
 * **Design Context:**
 *
 * This interface extends {@link TypeDefiningConstraint} which provides type identity for the constraint system.
 * Order constraints are one of the four primary constraint categories in evitaDB's query language (EvitaQL),
 * alongside {@link HeadConstraint}, {@link FilterConstraint}, and {@link RequireConstraint}.
 *
 * Order constraints are used in the {@link io.evitadb.api.query.order.OrderBy} container which wraps all ordering
 * specifications in a query. When multiple ordering constraints are specified, evitaDB applies them in a specific
 * manner: entities are first sorted by the first attribute, then entities **missing** the first attribute are
 * sorted by the second attribute, and so on. Entities with the same value for the primary sorting attribute are
 * **not** further sorted by subsequent attributes — they are instead ordered by primary key in ascending order.
 *
 * For traditional multi-level sorting behavior (where secondary attributes sort entities with equal primary values),
 * use sortable attribute compounds defined in the entity schema. These compounds pre-compute the multi-level sort
 * order into a single virtual attribute.
 *
 * **Common Implementations:**
 *
 * - **Attribute ordering**: `ascending`, `descending`
 * - **Price ordering**: `priceAscending`, `priceDescending`, `priceNatural`
 * - **Natural language ordering**: `natural` (for locale-aware text sorting)
 * - **Reference ordering**: `referenceProperty` (sort by attributes of referenced entities)
 * - **Random ordering**: `random` (randomized entity order, optionally stable with a seed)
 * - **Compound ordering**: `attributeSetExact`, `attributeSetInFilter` (for sorting by set membership)
 *
 * **Usage Example:**
 *
 * ```java
 * // Using order constraints via QueryConstraints factory methods
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         equals("visible", true)
 *     ),
 *     orderBy(
 *         descending("priority"),
 *         priceDescending(),
 *         ascending("name")
 *     )
 * );
 * ```
 *
 * **Thread Safety:**
 *
 * All implementations of this interface must be immutable and therefore thread-safe. Order constraints are
 * designed to be safely shared across multiple queries and threads without synchronization.
 *
 * @see io.evitadb.api.query.order.OrderBy
 * @see io.evitadb.api.query.QueryConstraints
 * @see TypeDefiningConstraint
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface OrderConstraint extends TypeDefiningConstraint<OrderConstraint> {

}
