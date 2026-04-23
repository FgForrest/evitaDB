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

import io.evitadb.dataType.SupportedEnum;

/**
 * Controls whether hierarchy nodes that have no queried entities associated with them are included or pruned from
 * the hierarchy statistics tree returned by {@link HierarchyOfReference} (and related hierarchy output constraints).
 *
 * A hierarchy node is considered *empty* when none of the entities that pass the current query filter are directly
 * associated with that node (or any of its descendants, when `QUERIED_ENTITY_COUNT` statistics are computed).
 *
 * - `LEAVE_EMPTY` — empty hierarchy nodes are preserved in the result tree. The tree structure mirrors the full
 *   hierarchy regardless of which parts have matching entities. Useful for UIs that must display the complete
 *   category tree and grey out or disable empty sections.
 * - `REMOVE_EMPTY` — empty hierarchy nodes are pruned from the result tree (default behaviour). Ancestor nodes
 *   that become leaf-less after pruning are also removed transitively. This produces a compact, meaningful tree
 *   that contains only categories with available products, which is the typical requirement for e-commerce
 *   faceted-navigation sidebars.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@SupportedEnum
public enum EmptyHierarchicalEntityBehaviour {

	/**
	 * Empty hierarchical nodes will remain in computed data structures.
	 */
	LEAVE_EMPTY,
	/**
	 * Empty hierarchical nodes are omitted from computed data structures (default behavior).
	 */
	REMOVE_EMPTY

}
