/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.statistics;

/**
 * The {@link CatalogStatisticsComponent#INDEX_SUMMARY} component - how many indexes the catalog holds in total.
 *
 * **Why only a total here.** The breakdown by index kind and scope - the thing that turns an opaque `indexCount` into
 * something a developer can act on - requires a pass over the index keys of a collection. Doing that for every
 * collection on every polled refresh is exactly the cost this API is shaped to avoid, so the breakdown lives at the
 * collection level ({@link CollectionIndexSummary}) and the catalog level reports the plain total, which each
 * collection answers from an `O(1)` map size.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog; indexes exist only in a loaded catalog.
 *
 * @param totalIndexCount total number of indexes across the whole catalog, including the catalog-level index
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record IndexSummaryStatistics(
	long totalIndexCount
) {
}
