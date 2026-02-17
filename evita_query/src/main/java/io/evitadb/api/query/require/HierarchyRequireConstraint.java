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

import javax.annotation.Nullable;

/**
 * Marker interface for require constraints that represent a *named hierarchy output segment* — one of the logical
 * parts of a hierarchy result that can be computed by {@link HierarchyOfSelf} or {@link HierarchyOfReference}.
 *
 * Each implementation corresponds to a distinct traversal strategy and is identified by a developer-assigned
 * output name returned by {@link #getOutputName()}. The output name is the key under which the computed hierarchy
 * data structure is stored in the extra-result map of the query response, allowing a single query to request
 * multiple hierarchy views simultaneously (e.g., a full menu from root *and* a breadcrumb trail to the current node).
 *
 * Because hierarchy output is part of the extra-result family, this interface also extends
 * {@link ExtraResultRequireConstraint}, ensuring the engine's `ExtraResultPlanningVisitor` schedules the
 * corresponding hierarchy producer.
 *
 * Concrete implementations:
 * - {@link HierarchyFromRoot} — full tree starting from the virtual invisible root
 * - {@link HierarchyFromNode} — sub-tree rooted at a specific named node
 * - {@link HierarchyChildren} — direct/transitive children of the currently-filtered hierarchy node
 * - {@link HierarchyParents} — ancestor chain of the currently-filtered hierarchy node
 * - {@link HierarchySiblings} — sibling nodes at the same level as the currently-filtered node
 *
 * Each of these may carry {@link HierarchyOutputRequireConstraint} children to further customise what data
 * (entity bodies, statistics, traversal bounds) is returned per node.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyRequireConstraint extends RequireConstraint, ExtraResultRequireConstraint, HierarchyConstraint<RequireConstraint> {

	/**
	 * Returns the key the computed extra result should be registered to.
	 *
	 * @return the output name, or null if default naming should be used
	 */
	@Nullable
	String getOutputName();
}
