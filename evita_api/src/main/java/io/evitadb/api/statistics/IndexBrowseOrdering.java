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
 * Every value walks every index of the collection - the walk is unavoidable and is what makes browsing an explicit
 * drill-down rather than something to poll. What they differ in is how much of that walk has to be *kept*, and what
 * the kept entries are ranked by.
 *
 * {@link #MAP_ORDER} keeps only the requested window and is the one order that is stable for a given catalog version;
 * every other value builds its page through a bounded heap and is a **top-N view**. The four orders that rank by an
 * activity counter carry caveats the entity-count one does not; they are documented once, on
 * {@link #BY_QUERY_COUNT_DESC}, and referred to from the other three.
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
	 * **A catalog browse has nothing for *this* order to rank by**, where the four counter orders do rank it. A catalog
	 * index reports no entity count - it maintains no primary-key bitmap - so this ordering degenerates there to the
	 * same total order {@link #MAP_ORDER} yields, over the one index per scope a catalog holds. It is accepted rather
	 * than rejected so that a client can send the same criteria to either owner.
	 *
	 * A page is built through a bounded heap of `pageNumber * pageSize` entries rather than a full sort, so a shallow
	 * page over a large collection costs `O(indexes * log(pageNumber * pageSize))` in time and only
	 * `O(pageNumber * pageSize)` in space. That advantage degrades with *depth*: this is a top-N access pattern, and
	 * paging deep into it is the wrong question to ask of it.
	 *
	 * There is deliberately no ordering by measured memory. Entity count is a single `O(1)` bitmap cardinality, while
	 * a heap measurement has to traverse an index's whole contents - ordering by it would mean measuring *every* index
	 * in the collection on every call, which is exactly the cost this surface is shaped to avoid. That measurement
	 * does exist, one index at a time, on {@link IndexDetail}: a client browses to find the candidates
	 * and measures the few it chose. Were the same reading ever added to {@link BrowsedIndex} it would belong to the
	 * page already selected and still could not be used to select it.
	 */
	BY_ENTITY_COUNT_DESC,

	/**
	 * Most-queried first, by {@link BrowsedIndex#queryCount()}, with ties broken deterministically by index kind, then
	 * scope, then discriminator.
	 *
	 * This is the order that answers *which indexes are earning the memory they occupy*. Read the count against
	 * {@link BrowsedIndex#observedSince()} rather than on its own: it is a total over an observation window that
	 * begins per index, so two rows are only comparable once each has been divided by its own window. Unlike
	 * {@link #BY_ENTITY_COUNT_DESC}, this order means something on a catalog browse as well - a catalog index is
	 * chosen by queries and maintained by writes like any other.
	 *
	 * **The rank is a best-effort scan reading, not an atomic snapshot.** The counters move under live traffic, and
	 * the walk samples each candidate at a different moment, so the page describes no single instant of the
	 * collection's history. What it does guarantee is internal consistency: a candidate's counter is read exactly
	 * once during the walk, and the value a row reports for the counter it was ranked by *is* the value that placed
	 * it - no row can contradict its own position, however busy that index became afterwards. A row's other activity
	 * readings are taken separately and are correspondingly fresher, which is deliberate; only the ranking counter
	 * has a position to stay consistent with.
	 *
	 * **Pages are unstable across calls, and no field of the result says otherwise.** Recording activity does not
	 * advance the catalog version, so two pages that agree on {@link IndexBrowseResult#catalogVersion()} were still
	 * ranked by keys that moved between them - an index can land on two pages of one paging run, or on none. That is
	 * a property of ranking by a moving key rather than a defect to be fixed by a better tiebreaker. Ask this order
	 * for the first page or two and act on it; a client that must enumerate the whole set pages in {@link #MAP_ORDER}
	 * instead, which is stable for a given catalog version and cheaper at every depth.
	 *
	 * The cost class is exactly that of {@link #BY_ENTITY_COUNT_DESC}: one `O(1)` read per surviving candidate and a
	 * bounded heap of `pageNumber * pageSize` entries, deep paging capped the same way. Nothing here is paid on the
	 * query or the write path - the counters are maintained there whether or not anybody ever browses.
	 */
	BY_QUERY_COUNT_DESC,

	/**
	 * Least-queried first, by {@link BrowsedIndex#queryCount()}, with the same tiebreaker - the drop-candidate hunt.
	 *
	 * **It is tie-dominated at zero, by the nature of what it is for.** On most catalogs the great majority of
	 * indexes have never been chosen by a query, so the head of this order is one large block of equal zeros rather
	 * than a ranking. The tiebreaker - index kind, then scope, then discriminator - is the whole of what makes a page
	 * boundary drawn inside that block reproducible; ranking on the counter alone would let each request re-permute
	 * the zeros, showing some indexes twice while never showing others.
	 *
	 * A zero is not by itself a verdict. It is a statement about the window {@link BrowsedIndex#observedSince()}
	 * opens, and an index created a minute ago says far less by its zero than one observed since the catalog loaded.
	 *
	 * The caveats documented on {@link #BY_QUERY_COUNT_DESC} apply here unchanged.
	 */
	BY_QUERY_COUNT_ASC,

	/**
	 * Most-updated first, by {@link BrowsedIndex#updateCount()}, with ties broken deterministically by index kind,
	 * then scope, then discriminator.
	 *
	 * Paired with {@link #BY_QUERY_COUNT_DESC} this is what finds indexes maintained far more often than they are
	 * read - the ones whose write cost is not being repaid. Mind what {@link BrowsedIndex#updateCount()} documents
	 * about the maintenance it counts before acting on the head of this order: a `GLOBAL` index leads it on
	 * essentially every catalog and is never a drop candidate, and maintenance driven by a cross-collection trigger
	 * is not counted at all, so an index sitting low here may still be doing work.
	 *
	 * The caveats documented on {@link #BY_QUERY_COUNT_DESC} apply here unchanged.
	 */
	BY_UPDATE_COUNT_DESC,

	/**
	 * Least-updated first, by {@link BrowsedIndex#updateCount()}, with the same tiebreaker - the order that surfaces
	 * indexes nothing is writing to.
	 *
	 * Tie-dominated at zero exactly as {@link #BY_QUERY_COUNT_ASC} is, and reproducible inside that block for the
	 * same reason: with every count equal, the kind-then-scope-then-discriminator tiebreaker is the only thing a page
	 * boundary drawn through the zeros can rely on.
	 *
	 * A never-updated index is not thereby a drop candidate - it may be precisely the one every query reads - which
	 * is why this order is worth reading beside {@link #BY_QUERY_COUNT_ASC} rather than acted on alone.
	 *
	 * The caveats documented on {@link #BY_QUERY_COUNT_DESC} apply here unchanged.
	 */
	BY_UPDATE_COUNT_ASC

}
