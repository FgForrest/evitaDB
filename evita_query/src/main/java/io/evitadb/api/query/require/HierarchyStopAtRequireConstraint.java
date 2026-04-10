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
 * Marker interface for require constraints that can be placed inside a {@link HierarchyStopAt} container to
 * define the *tree traversal stop condition* for a hierarchy output computation.
 *
 * The {@link HierarchyStopAt} constraint wraps exactly one `HierarchyStopAtRequireConstraint` to specify at
 * what point the hierarchy traversal should cease, preventing the engine from recursing deeper into the tree.
 *
 * Concrete implementations:
 * - {@link HierarchyLevel} — stops traversal when a specific absolute level from the root is reached
 * - {@link HierarchyDistance} — stops traversal when the node is more than N steps away from the starting node
 * - {@link HierarchyNode} — stops traversal at a node that satisfies a given filter constraint
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyStopAtRequireConstraint extends RequireConstraint, HierarchyConstraint<RequireConstraint> {
}
