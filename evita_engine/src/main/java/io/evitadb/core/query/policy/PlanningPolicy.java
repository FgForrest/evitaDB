/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.policy;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetchRequire;

import javax.annotation.Nonnull;

/**
 * Planning policy allows us to guide the query planner in the direction we want. This is particularly used for handling
 * {@link DebugMode} requirements and verify all possible query paths in our automated tests.
 *
 * The policy governs entity prefetch only - whether it is allowed and what it is estimated to cost.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface PlanningPolicy {

	/**
	 * Method implementation return prefetch policy - i.e. whether it should be forbidden or allowed.
	 */
	@Nonnull
	PrefetchPolicy getPrefetchPolicy();

	/**
	 * Returns estimated prefetch costs for the given requirements and entity count.
	 * @param prefetchedEntityCount number of entities that should be prefetched
	 * @param requirements requirements for the prefetch
	 * @param preferredByUser whether the prefetch is preferred by the user via debug options
	 * @return estimated prefetch costs
	 */
	long estimatePrefetchCost(
		int prefetchedEntityCount,
		@Nonnull EntityFetchRequire requirements,
		boolean preferredByUser
	);

	/**
	 * Policy allowing or denying prefetch.
	 */
	enum PrefetchPolicy {

		ALLOW, DENY

	}

}
