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

import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.RequireConstraint;

/**
 * Marker interface for the top-level hierarchy require constraints that are placed directly in the `require` clause
 * of a query to trigger computation of one or more hierarchy output segments.
 *
 * A query may include at most one `HierarchyOfSelf` and multiple `HierarchyOfReference` constraints (one per
 * distinct reference type). The engine's `ExtraResultPlanningVisitor` identifies all `RootHierarchyConstraint`
 * instances in the require clause and schedules the appropriate `HierarchyStatisticsProducer` computations.
 *
 * Concrete implementations:
 * - {@link HierarchyOfSelf} — requests hierarchy data for the *queried entity type itself* (applicable when the
 *   queried entity is a hierarchical entity, e.g. a category browsing its own category tree)
 * - {@link HierarchyOfReference} — requests hierarchy data for a *referenced hierarchical entity type*
 *   (e.g. a product query computing the category tree for all referenced categories)
 *
 * Each root hierarchy constraint carries one or more {@link HierarchyRequireConstraint} children that describe
 * the individual named output segments (e.g. `fromRoot("menu", ...)`, `children("submenu", ...)`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface RootHierarchyConstraint extends HierarchyConstraint<RequireConstraint>, RequireConstraint {
}
