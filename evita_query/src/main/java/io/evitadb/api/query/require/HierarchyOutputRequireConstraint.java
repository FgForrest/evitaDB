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
 * Marker interface for require constraints that configure *what data is computed and returned* for each node in
 * a hierarchy output structure. Implementations of this interface are valid children of the hierarchy traversal
 * containers that generate segments of the navigation tree:
 *
 * - {@link HierarchyChildren} — the sub-tree of direct/transitive children of the currently-filtered node
 * - {@link HierarchyParents} — the ancestor chain from the currently-filtered node up to the root
 * - {@link HierarchyFromRoot} — the complete tree from the virtual root, unaffected by hierarchy filters
 * - {@link HierarchyFromNode} — a sub-tree rooted at an arbitrary node
 *
 * Concrete implementations include:
 * - {@link EntityFetch} — requests entity body data to be loaded for each hierarchy node
 * - {@link HierarchyStatistics} — requests per-node statistics (count of directly/transitively matching entities)
 * - {@link HierarchyStopAt} — defines the traversal stop condition; wraps a {@link HierarchyStopAtRequireConstraint}
 *
 * The distinction from {@link HierarchyRequireConstraint} (which is placed directly inside
 * {@link HierarchyOfSelf} / {@link HierarchyOfReference}) is one of nesting level: `HierarchyOutputRequireConstraint`
 * instances configure individual named output segments, while `HierarchyRequireConstraint` instances *are* the named
 * output segments.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyOutputRequireConstraint extends RequireConstraint, HierarchyConstraint<RequireConstraint> {
}
