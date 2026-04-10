/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.dataType.SupportedEnum;

/**
 * Specifies which subset of the `filterBy` constraint is used as the base entity set when computing statistics
 * for the {@link HierarchyStatistics} requirement. This distinction matters for interactive UIs that combine
 * mandatory system filters with optional user-controlled filters — the `userFilter` container separates these two
 * concerns.
 *
 * - `COMPLETE_FILTER` — statistics are computed over the full result set produced by the entire `filterBy`,
 *   including any constraints inside `userFilter`. Use this when you want the counts to reflect the currently
 *   active user selection.
 * - `WITHOUT_USER_FILTER` — the `userFilter` container and all its children are excluded before computing
 *   statistics. The counts therefore reflect only the mandatory filter criteria, independent of user choices.
 *   This is the default value (implicit argument in {@link HierarchyStatistics}) and the most common mode for
 *   category-tree sidebars: the category counts remain stable as the user changes other filters.
 * - `COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER` — similar to `COMPLETE_FILTER`, but any filter constraints
 *   inside `userFilter` that limit the same hierarchical entity type as the one this statistics constraint is
 *   applied to are excluded. This prevents self-referential filtering from collapsing the hierarchy statistics
 *   of the hierarchy being navigated.
 *
 * `WITHOUT_USER_FILTER` is the default and is treated as an implicit argument in {@link HierarchyStatistics},
 * meaning it is omitted from the EvitaQL string representation when not overridden.
 */
@SupportedEnum
public enum StatisticsBase {

	/**
	 * Complete `filterBy` constraint output will be considered when calculating statistics of the queried entities.
	 */
	COMPLETE_FILTER,
	/**
	 * Contents of the `filterBy` excluding `userFilter` and its children will be considered when calculating statistics
	 * of the queried entities.
	 */
	WITHOUT_USER_FILTER,
	/**
	 * Complete `filterBy` constraint output excluding constraints within `userFilter` limiting references of the same
	 * hierarchical entity type this constraint is applied to will be considered when calculating statistics of
	 * the queried entities.
	 */
	COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER

}
