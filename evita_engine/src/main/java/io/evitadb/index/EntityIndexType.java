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

package io.evitadb.index;

import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.data.structure.Entity;

/**
 * EntityIndexType enumeration keeps constants for all types of {@link EntityIndex EntityIndexes} that are used to
 * find data about {@link Entity entities} quickly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public enum EntityIndexType {
	/**
	 * Global index is the main index with all record ids of particular {@link Entity#getType()}.
	 * It's the slowest index possible and can be compared to SQL DB full-scan. When accessing this index it means
	 * there is no better index usable for this particular query.
	 */
	GLOBAL,
	/**
	 * Index that contains referenced entity ids that are connected with special {@link Entity#getType()}.
	 * This index is used when query contains {@link ReferenceHaving} query
	 * is used.
	 */
	REFERENCED_ENTITY_TYPE,
	/**
	 * Index that contains record ids that are connected with certain referenced {@link Entity#getType()} and {@link Entity#getPrimaryKey()}.
	 * This index is used when query contains {@link ReferenceHaving} query
	 * is used.
	 */
	REFERENCED_ENTITY,
	/**
	 * Index that contains the similar data as {@link #GLOBAL} index but only for those entities that are referencing
	 * entity with {@link HierarchicalPlacementContract} defined.
	 */
	REFERENCED_HIERARCHY_NODE
}
