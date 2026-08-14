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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Set;

/**
 * What to select, how to order it and which page of it to return, for one index browse.
 *
 * **Filters are conjunctive across categories and disjunctive within one.** An index must match every non-empty
 * category, and matches a category by being any one of its values. An empty set means that category does not filter -
 * never that nothing matches.
 *
 * Every filter reads off the index's key alone, so filtering allocates nothing and touches no index contents. That is
 * true of the *selection*; {@link IndexBrowseOrdering#BY_ENTITY_COUNT_DESC} additionally reads a counter from each
 * surviving index, which is an `O(1)` bitmap cardinality but not free.
 *
 * @param pageNumber     which page to return, 1-indexed - page 1 is the first page
 * @param pageSize       how many indexes the page holds, at most {@link #MAX_PAGE_SIZE}
 * @param ordering       the order to impose before the page is cut out of the result
 * @param indexTypes     kinds to keep, or an empty set to keep every kind
 * @param scopes         scopes to keep, or an empty set to keep every scope
 * @param referenceNames names of the references whose indexes to keep, or an empty set to keep them regardless of
 *                       reference; naming a reference the entity schema does not declare is an error rather than an
 *                       empty page, so that a typo cannot read as "this reference has no indexes"
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexBrowseResult
 */
public record IndexBrowseCriteria(
	int pageNumber,
	int pageSize,
	@Nonnull IndexBrowseOrdering ordering,
	@Nonnull Set<EntityIndexType> indexTypes,
	@Nonnull Set<Scope> scopes,
	@Nonnull Set<String> referenceNames
) {

	/**
	 * The largest page an index browse will serve.
	 *
	 * This surface enforces a maximum where the task-status listing deliberately does not. That listing is safe
	 * without one because the number of tasks is small; the number of indexes is not, and both factors of the
	 * bounded-heap cost of {@link IndexBrowseOrdering#BY_ENTITY_COUNT_DESC} are `pageNumber * pageSize` - i.e. wholly
	 * client-controlled. An unbounded page size would let one request materialise the entire index set that the heap
	 * exists to avoid holding.
	 *
	 * Exceeding it is rejected rather than silently clamped: a clamped page looks identical to a complete one, and a
	 * client paging by "did I get a full page back" would stop early and believe it had seen everything.
	 */
	public static final int MAX_PAGE_SIZE = 1000;

	/**
	 * How deep {@link IndexBrowseOrdering#BY_ENTITY_COUNT_DESC} may be paged, counted in indexes rather than pages.
	 *
	 * Capping the page size alone does not bound that ordering. Its heap retains everything up to the *end* of the
	 * requested page, so the window is `pageNumber * pageSize` - and with the page number unbounded, a request for a
	 * far-out page retains every matching index and then sorts all of them, only to return an empty page. On a
	 * collection with hundreds of thousands of indexes that turns one cheap-looking request into a full sort and a
	 * proportional allocation, which is the opposite of what the bounded heap exists to guarantee.
	 *
	 * {@link IndexBrowseOrdering#MAP_ORDER} needs no such limit: it counts matches as it walks and materialises only
	 * the window, so its allocation is `O(pageSize)` however deep the page is. The limit is therefore attached to the
	 * ordering that needs it rather than to paging in general.
	 *
	 * Deep paging into a size ordering is in any case the wrong question - it is a top-N access pattern, and a client
	 * that wants everything should page in {@link IndexBrowseOrdering#MAP_ORDER}, which is cheaper at every depth.
	 */
	public static final int MAX_SIZE_ORDERED_WINDOW = 10_000;

	public IndexBrowseCriteria {
		Objects.requireNonNull(ordering, "Ordering must not be null!");
		Objects.requireNonNull(indexTypes, "Index kinds must not be null!");
		Objects.requireNonNull(scopes, "Scopes must not be null!");
		Objects.requireNonNull(referenceNames, "Reference names must not be null!");
		Assert.isTrue(
			pageNumber >= 1,
			"Page number must be a positive number (1-indexed), but was " + pageNumber + "!"
		);
		Assert.isTrue(
			pageSize >= 1,
			"Page size must be a positive number, but was " + pageSize + "!"
		);
		Assert.isTrue(
			pageSize <= MAX_PAGE_SIZE,
			"Page size must not exceed " + MAX_PAGE_SIZE + ", but was " + pageSize + "!"
		);
		if (ordering == IndexBrowseOrdering.BY_ENTITY_COUNT_DESC) {
			// computed in long arithmetic because both factors are client-supplied and their product overflows int
			// long before it reaches the limit - an overflowed window would wrap negative and pass the check
			final long window = (long) pageNumber * pageSize;
			Assert.isTrue(
				window <= MAX_SIZE_ORDERED_WINDOW,
				"Ordering by entity count retains every index up to the end of the requested page, so page " +
					pageNumber + " of size " + pageSize + " would retain " + window + " indexes - more than the " +
					MAX_SIZE_ORDERED_WINDOW + " this ordering allows. Page in `" + IndexBrowseOrdering.MAP_ORDER +
					"` to walk the whole set instead; it costs the same at any depth!"
			);
		}
	}

}
