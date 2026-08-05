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
 * The order in which {@link BrowsedIndex} entries are returned by an index browse.
 *
 * Both values walk every index of the collection - the walk is unavoidable and is what makes browsing an explicit
 * drill-down rather than something to poll. What they differ in is how much of that walk has to be *kept*.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexBrowseCriteria
 */
public enum IndexBrowseOrdering {

	/**
	 * The order the indexes happen to sit in inside the collection's index map.
	 *
	 * Arbitrary but *stable* for a given catalog version, which is what pagination needs: matches are counted during
	 * the walk and only the requested window is materialised, so the cost of a page is `O(indexes)` in time and
	 * `O(pageSize)` in allocation.
	 *
	 * Being tied to the internal map layout, this order carries no meaning a client should read into - it is the
	 * cheapest way to enumerate everything, nothing more. It is the right choice for exhaustive paging through the
	 * whole set; use {@link #BY_ENTITY_COUNT_DESC} to find the large indexes instead.
	 */
	MAP_ORDER,

	/**
	 * Largest first, by {@link BrowsedIndex#entityCount()}, with ties broken deterministically by index kind, then
	 * scope, then discriminator.
	 *
	 * The tiebreaker is not a nicety. Real collections are tie-dominated - most per-referenced-entity indexes hold a
	 * handful of entities each - so ordering on the count alone would let page 2 re-walk into a different permutation
	 * of the same tie block, silently showing the client duplicates while hiding other indexes entirely.
	 *
	 * A page is built through a bounded heap of `pageNumber * pageSize` entries rather than a full sort, so a shallow
	 * page over a large collection costs `O(indexes * log(pageNumber * pageSize))` in time and only
	 * `O(pageNumber * pageSize)` in space. That advantage degrades with *depth*: this is a top-N access pattern, and
	 * paging deep into it is the wrong question to ask of it.
	 *
	 * There is deliberately no ordering by estimated memory. Entity count is a single `O(1)` bitmap cardinality, while
	 * a memory estimate has to traverse an index's contents - ordering by it would mean estimating *every* index in
	 * the collection on every call, which is exactly the cost this surface is shaped to avoid. Should a per-index
	 * memory reading ever be added to {@link BrowsedIndex}, it would belong to the page that was already selected and
	 * still could not be used to select it; none is reported today - see
	 * {@link CatalogStatisticsComponent#MEMORY_FOOTPRINT} for why.
	 */
	BY_ENTITY_COUNT_DESC

}
