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
 * Marker interface for constraints that specify additional data requirements and computational requests in an evitaDB
 * query. Require constraints control what data is fetched for matched entities, what extra computations are performed,
 * and how results are paginated — but they **never** affect which entities are matched or their ordering.
 *
 * Require constraints have no direct parallel in SQL. While they superficially resemble `SELECT` clauses (controlling
 * what data is returned), they also encompass pagination settings, extra computations (histograms, statistics,
 * facet summaries, hierarchy trees), and contextual data fetching (associated prices, attributes, references).
 *
 * **Design Context:**
 *
 * This interface extends {@link TypeDefiningConstraint} which provides type identity for the constraint system.
 * Require constraints are one of the four primary constraint categories in evitaDB's query language (EvitaQL),
 * alongside {@link HeadConstraint}, {@link FilterConstraint}, and {@link OrderConstraint}.
 *
 * Require constraints are used in the {@link io.evitadb.api.query.require.Require} container which wraps all
 * requirement specifications in a query. By default, evitaDB returns only the primary keys of matched entities.
 * To receive entity bodies, attributes, associated data, prices, or references, explicit require constraints must
 * be provided.
 *
 * **Common Implementations:**
 *
 * - **Entity fetching**: `entityFetch`, `entityFetchAll`, `attributeContent`, `associatedDataContent`,
 *   `priceContent`, `referenceContent`
 * - **Pagination**: `page`, `strip` (for offset-based and cursor-based pagination)
 * - **Extra computations**: `facetSummary`, `attributeHistogram`, `priceHistogram`, `hierarchyOfSelf`,
 *   `hierarchyOfReference`
 * - **Statistics**: `queryTelemetry` (for performance profiling)
 * - **Data localization**: `dataInLocales` (for fetching localized content)
 *
 * **Key Behavioral Contracts:**
 *
 * - Require constraints **never filter** entities — they only control what data is returned and what extra
 *   computations are performed.
 * - Most require constraints are independent and can be combined freely. Some have implicit dependencies (e.g.,
 *   `attributeHistogram` requires the filtered attributes to exist in the schema).
 * - Entity fetching constraints define a "projection" — which parts of the entity should be hydrated. Without
 *   explicit fetching requirements, only entity primary keys are returned.
 *
 * **Usage Example:**
 *
 * ```java
 * // Using require constraints via QueryConstraints factory methods
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             equals("visible", true),
 *             priceBetween(100, 1000)
 *         )
 *     ),
 *     orderBy(
 *         priceDescending()
 *     ),
 *     require(
 *         page(1, 20),
 *         entityFetch(
 *             attributeContent("code", "name"),
 *             priceContentRespectingFilter(),
 *             referenceContent("brand")
 *         ),
 *         facetSummary(COUNTS),
 *         priceHistogram(20)
 *     )
 * );
 * ```
 *
 * **Thread Safety:**
 *
 * All implementations of this interface must be immutable and therefore thread-safe. Require constraints are
 * designed to be safely shared across multiple queries and threads without synchronization.
 *
 * @see io.evitadb.api.query.require.Require
 * @see io.evitadb.api.query.QueryConstraints
 * @see TypeDefiningConstraint
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface RequireConstraint extends TypeDefiningConstraint<RequireConstraint> {

}
