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
 * The enum specifies whether the hierarchy statistics cardinality will be based on a complete query filter by
 * constraint or only the part without user defined filter.
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
