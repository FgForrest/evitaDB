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
 * Specifies which type of count is computed for each hierarchy node in a {@link HierarchyStatistics} result.
 * The two values are independent and can be combined — both can be requested simultaneously by passing multiple
 * values to {@link HierarchyStatistics}. When no value is provided the statistics block is still produced but
 * contains neither count, which is valid when callers only need the structural node information.
 *
 * - `CHILDREN_COUNT` — the number of direct and indirect (all descendant) hierarchy nodes that exist below the
 *   given node in the tree, **regardless** of whether those children pass the query filter. This count is always
 *   accurate even when the tree traversal is limited by a `stopAt` constraint. It is relatively cheap to compute
 *   because it can prune dead branches early.
 * - `QUERIED_ENTITY_COUNT` — the total number of entities that would appear in the primary result set if the
 *   query were narrowed to this specific hierarchy node via a `hierarchyWithin` filter constraint. This metric is
 *   considerably more expensive than `CHILDREN_COUNT` because it requires evaluating the full query formula for
 *   every node; the engine must count entities all the way to the leaves. For large datasets or root-level nodes
 *   this can be very slow — caching the result is strongly recommended.
 *
 * The base entity set used for `QUERIED_ENTITY_COUNT` is controlled by the {@link StatisticsBase} argument of
 * the parent {@link HierarchyStatistics} constraint.
 */
@SupportedEnum
public enum StatisticsType {

	/**
	 * Triggers calculation of the count of child hierarchy nodes that exist in the hierarchy tree below the given
	 * node; the count is correct regardless of whether the children themselves are requested/traversed by
	 * the constraint definition.
	 */
	CHILDREN_COUNT,
	/**
	 * Triggers the calculation of the total number of queried entities that would be returned if the current query
	 * is focused on this particular hierarchy node using the hierarchyWithin filter constraint.
	 */
	QUERIED_ENTITY_COUNT

}
