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

package io.evitadb.api.query.require;

/**
 * Marker interface for require constraints whose evaluation produces an *extra result* object in the query response,
 * separate from the primary entity list.
 *
 * While entity-fetch constraints ({@link EntityFetchRequire}, {@link EntityContentRequire}) control *what data is
 * returned per entity*, extra-result constraints instruct the engine to compute additional aggregated data structures
 * that accompany the primary result set. These computations may involve additional index traversals or aggregation
 * passes over the full filtered result set.
 *
 * Concrete implementations include:
 * - {@link AttributeHistogram} — computes value-distribution histograms for numeric/range attributes
 * - {@link PriceHistogram} — computes a value-distribution histogram over entity prices
 * - {@link FacetSummary} / {@link FacetSummaryOfReference} — computes facet option counts and impact predictions
 * - {@link HierarchyOfSelf} / {@link HierarchyOfReference} — compute hierarchical tree structures for navigation
 *   menus (which also implement the more specific {@link HierarchyRequireConstraint})
 *
 * The `ExtraResultPlanningVisitor` in the engine identifies all constraints implementing this interface during
 * query plan construction and schedules the corresponding extra-result producers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ExtraResultRequireConstraint {
}
