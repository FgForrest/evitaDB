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

import javax.annotation.Nonnull;

/**
 * Abstraction for accumulating {@link EntityContentRequire} constraints from multiple independent sources within
 * a single query planning pass.
 *
 * During query plan construction, entity content requirements can originate from three different places:
 * 1. The explicit `require` clause in the user's query
 * 2. Ordering translators that need certain data loaded before they can sort (e.g., attribute ordering requires
 *    the relevant {@link AttributeContent} to be present)
 * 3. Filtering translators that need certain data available for in-memory filtering during prefetch
 *
 * Instead of each of these places independently constructing its own {@link EntityFetch}, they all contribute
 * their implicit requirements through this collector interface. The collector then merges compatible requirements
 * (using the {@link EntityContentRequire#isCombinableWith} / {@link EntityContentRequire#combineWith} protocol)
 * and deduplicates requirements that are fully contained within already-registered ones.
 *
 * The standard implementation is {@link DefaultPrefetchRequirementCollector}, which is wired into
 * `QueryPlanningContext` and `QueryPlanBuilder` so that all engine translators can access the same collector
 * instance for a given query. Callers that do not need prefetch (e.g. in contexts where entity data will be loaded
 * via a different path) may use a no-op implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface FetchRequirementCollector {

	/**
	 * Registers new requirements that should be taken into account when/if the prefetch of the entities occurs.
	 * The method call might be completely ignored if the collector is not present.
	 *
	 * @param require the requirements to prefetch
	 */
	void addRequirementsToPrefetch(@Nonnull EntityContentRequire... require);

	/**
	 * Retrieves the list of entity content requirements that are scheduled for prefetching. These requirements
	 * may have been combined or deduplicated during the collection process.
	 *
	 * @return an array of {@link EntityContentRequire} representing the requirements to prefetch
	 */
	@Nonnull
	EntityContentRequire[] getRequirementsToPrefetch();

}
