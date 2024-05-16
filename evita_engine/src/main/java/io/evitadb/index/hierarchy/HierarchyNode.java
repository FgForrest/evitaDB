/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.index.hierarchy;

import javax.annotation.Nullable;

/**
 * This DTO represents information about single node in the hierarchy tree. It contains all information necessary to
 * construct the entire tree.
 *
 * @param entityPrimaryKey       Primary key of the entity that is represented by this node.
 * @param parentEntityPrimaryKey Primary key of the entity that is declared as parent of this node.
 *                               Might be null if node has no parent.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record HierarchyNode(
	int entityPrimaryKey,
	@Nullable Integer parentEntityPrimaryKey
) implements Comparable<HierarchyNode> {
	@Override
	public int compareTo(HierarchyNode o) {
		return Integer.compare(entityPrimaryKey, o.entityPrimaryKey);
	}

}
