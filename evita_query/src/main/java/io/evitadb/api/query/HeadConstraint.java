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
 * Marker interface for constraints that appear in the head (header) section of an evitaDB query. Head constraints
 * specify meta-information about the query itself, most importantly the target entity collection to query against.
 *
 * The head section of a query is distinct from the filtering, ordering, and requirement sections. It establishes
 * the context in which the query executes — what collection is being queried and any metadata labels for debugging
 * or monitoring purposes.
 *
 * **Design Context:**
 *
 * This interface extends {@link TypeDefiningConstraint} which provides type identity for the constraint system.
 * Head constraints are one of the four primary constraint categories in evitaDB's query language (EvitaQL),
 * alongside {@link FilterConstraint}, {@link OrderConstraint}, and {@link RequireConstraint}.
 *
 * Head constraints are typically used in the {@link io.evitadb.api.query.head.Head} container, though in simple
 * cases a single {@link io.evitadb.api.query.head.Collection} constraint can appear directly in the query without
 * an explicit container. The `Head` container becomes necessary when combining the collection specification with
 * query labels for debugging or telemetry purposes.
 *
 * **Common Implementations:**
 *
 * - **Collection targeting**: `collection` (specifies which entity collection to query — this is **mandatory**
 *   in every query that operates on a specific entity type)
 * - **Query metadata**: `label` (attaches key-value labels to a query for debugging, logging, or performance
 *   analysis purposes)
 *
 * **Key Behavioral Contracts:**
 *
 * - Every query that operates on an entity collection **must** include a `collection` constraint. The only exception
 *   is catalog-level queries that query across all collections.
 * - `Label` constraints are purely informational and have no effect on query execution or results — they exist
 *   solely for observability.
 * - Head constraints are processed before any filtering, ordering, or data fetching occurs.
 *
 * **Usage Example:**
 *
 * ```java
 * // Simple query with collection constraint directly in the head
 * Query query = query(
 *     collection("Product"),
 *     filterBy(equals("visible", true))
 * );
 *
 * // Query with explicit head container including labels
 * Query query = query(
 *     head(
 *         collection("Product"),
 *         label("query-name", "featured-products-query"),
 *         label("source", "homepage")
 *     ),
 *     filterBy(equals("featured", true)),
 *     orderBy(descending("priority"))
 * );
 * ```
 *
 * **Thread Safety:**
 *
 * All implementations of this interface must be immutable and therefore thread-safe. Head constraints are
 * designed to be safely shared across multiple queries and threads without synchronization.
 *
 * @see io.evitadb.api.query.head.Head
 * @see io.evitadb.api.query.head.Collection
 * @see io.evitadb.api.query.head.Label
 * @see io.evitadb.api.query.QueryConstraints
 * @see TypeDefiningConstraint
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface HeadConstraint extends TypeDefiningConstraint<HeadConstraint> {
}
