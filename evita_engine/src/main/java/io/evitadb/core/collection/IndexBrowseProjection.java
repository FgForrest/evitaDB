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

package io.evitadb.core.collection;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.EntityIndexKind;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Projects an entity collection's live indexes into one page of {@link BrowsedIndex} descriptors.
 *
 * This is the counterpart of {@link IndexCardinalityProjection}, and it makes the opposite trade. That one is
 * deliberately proportional to the *schema*: it constructs the keys it wants and looks each up in `O(1)`, so it never
 * visits the per-referenced-entity indexes that dominate a production collection. Browsing cannot do that, because the
 * indexes it exists to find are exactly the ones that are not derivable from the schema. Every call therefore walks
 * the whole map, and the surface is documented as a drill-down rather than something to poll.
 *
 * **What the walk avoids allocating.** Iteration runs over `keySet()`, whose `ChampKeyIterator` yields keys without
 * materialising an entry object per element - unlike `entrySet()`. Every filter reads off the key alone, so an index
 * that fails a filter is never fetched and costs nothing but the comparison. Values are fetched only for indexes that
 * survive filtering, and under {@link IndexBrowseOrdering#MAP_ORDER} only for those inside the requested window.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexBrowseCriteria
 */
final class IndexBrowseProjection {

	/**
	 * The order the caller asked for, as a comparator: entity count descending, ties broken by the index key.
	 *
	 * The tiebreaker exists because the data is tie-dominated - most per-referenced-entity indexes cover a handful of
	 * entities each - and an unstable order silently corrupts pagination, repeating some indexes across pages while
	 * omitting others. {@link EntityIndexKey} already compares by type, then scope, then discriminator, which is a
	 * total order over the keys of one collection and therefore leaves no ties for chance to resolve.
	 */
	private static final Comparator<BrowseCandidate> ENTITY_COUNT_ORDER =
		Comparator.comparingInt(BrowseCandidate::entityCount).reversed()
			.thenComparing(BrowseCandidate::key);

	private IndexBrowseProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Selects, orders and pages the collection's indexes.
	 *
	 * @param indexes        the collection's index map, sealed by the caller so that the count and the page contents
	 *                       cannot come from two different states
	 * @param criteria       which indexes to select, in what order, and which page to return
	 * @param catalogVersion version of the catalog the sealed map was taken from, reported so a caller can tell that
	 *                       two pages of one browse describe two different index sets
	 * @return the requested page and the total number of matches behind it
	 */
	@Nonnull
	static IndexBrowseResult browse(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria,
		long catalogVersion
	) {
		// both factors are client-supplied and `pageNumber` is unbounded, so the window is computed in long
		// arithmetic - an int multiplication would overflow into a negative offset and quietly return page one
		final long offset = (long) (criteria.pageNumber() - 1) * criteria.pageSize();
		return switch (criteria.ordering()) {
			case MAP_ORDER -> {
				final List<BrowsedIndex> page = new ArrayList<>(criteria.pageSize());
				final int matchCount = collectInMapOrder(indexes, criteria, offset, page);
				yield toResult(criteria, catalogVersion, matchCount, page);
			}
			case BY_ENTITY_COUNT_DESC -> {
				final PriorityQueue<BrowseCandidate> heap = new PriorityQueue<>(ENTITY_COUNT_ORDER.reversed());
				final int matchCount = collectByEntityCount(indexes, criteria, offset, heap);
				yield toResult(criteria, catalogVersion, matchCount, cutPage(heap, criteria, offset, indexes));
			}
		};
	}

	/**
	 * Walks the map in its own order, counting every match and materialising a descriptor only inside the window.
	 *
	 * @param indexes  the sealed index map to walk
	 * @param criteria the selection to apply
	 * @param offset   how many matches precede the requested page
	 * @param page     accumulator the window's descriptors are appended to
	 * @return how many indexes matched in total
	 */
	private static int collectInMapOrder(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria,
		long offset,
		@Nonnull List<BrowsedIndex> page
	) {
		final long end = offset + criteria.pageSize();
		int matched = 0;
		for (final EntityIndexKey key : indexes.keySet()) {
			if (!matches(key, criteria)) {
				continue;
			}
			if (matched >= offset && matched < end) {
				page.add(describe(key, indexOf(indexes, key)));
			}
			matched++;
		}
		return matched;
	}

	/**
	 * Walks the map keeping only the best `offset + pageSize` matches seen so far, in a heap that evicts its own worst
	 * entry rather than growing with the collection.
	 *
	 * The heap is ordered *against* the requested order, so its head is the weakest entry retained and eviction is an
	 * `O(log k)` comparison against that head. This is what keeps a shallow page over a large collection from sorting
	 * hundreds of thousands of entries; the advantage narrows as `pageNumber` grows, which is why the surface
	 * documents size ordering as a top-N access pattern.
	 *
	 * @param indexes  the sealed index map to walk
	 * @param criteria the selection to apply
	 * @param offset   how many matches precede the requested page
	 * @param heap     accumulator retaining the best candidates
	 * @return how many indexes matched in total
	 */
	private static int collectByEntityCount(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria,
		long offset,
		@Nonnull PriorityQueue<BrowseCandidate> heap
	) {
		final long retained = offset + criteria.pageSize();
		int matched = 0;
		for (final EntityIndexKey key : indexes.keySet()) {
			if (!matches(key, criteria)) {
				continue;
			}
			matched++;
			// the count is read here rather than in the page cut, because ordering needs it for every match - it is
			// an O(1) cardinality of the index's primary-key bitmap, never a walk of the index contents
			final BrowseCandidate candidate = new BrowseCandidate(
				key, indexOf(indexes, key).getAllPrimaryKeys().size()
			);
			if (heap.size() < retained) {
				heap.offer(candidate);
			} else if (ENTITY_COUNT_ORDER.compare(candidate, heap.peek()) < 0) {
				heap.poll();
				heap.offer(candidate);
			}
		}
		return matched;
	}

	/**
	 * Drains the heap into the requested order and cuts the page out of it.
	 *
	 * @param heap     the retained candidates, in no useful order of their own
	 * @param criteria the page to cut
	 * @param offset   how many matches precede the requested page
	 * @param indexes  the sealed index map, for resolving the descriptors of the page's keys
	 * @return the page's descriptors, in the requested order
	 */
	@Nonnull
	private static List<BrowsedIndex> cutPage(
		@Nonnull PriorityQueue<BrowseCandidate> heap,
		@Nonnull IndexBrowseCriteria criteria,
		long offset,
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes
	) {
		final List<BrowseCandidate> ordered = new ArrayList<>(heap);
		ordered.sort(ENTITY_COUNT_ORDER);

		// the heap retained everything up to the end of the requested page, so the page starts at `offset` within it
		// - and `offset` is safe to narrow here because the heap never holds more than `offset + pageSize` entries
		final int from = (int) Math.min(offset, ordered.size());
		final int to = Math.min(from + criteria.pageSize(), ordered.size());
		final List<BrowsedIndex> page = new ArrayList<>(to - from);
		for (int i = from; i < to; i++) {
			final EntityIndexKey key = ordered.get(i).key();
			page.add(describe(key, indexOf(indexes, key)));
		}
		return page;
	}

	/**
	 * Assembles the result from the page that was cut and the number of matches behind it.
	 *
	 * @param criteria       the criteria the page was cut for, echoed back to the caller
	 * @param catalogVersion version of the catalog the page was read at
	 * @param matchCount     how many indexes matched in total, across every page
	 * @param page           the page's descriptors
	 * @return the assembled result
	 */
	@Nonnull
	private static IndexBrowseResult toResult(
		@Nonnull IndexBrowseCriteria criteria,
		long catalogVersion,
		int matchCount,
		@Nonnull List<BrowsedIndex> page
	) {
		return new IndexBrowseResult(
			catalogVersion,
			criteria.pageNumber(),
			criteria.pageSize(),
			matchCount,
			page.toArray(BrowsedIndex[]::new)
		);
	}

	/**
	 * Tells whether an index passes every filter of the criteria.
	 *
	 * Filters are conjunctive across categories and disjunctive within one, and an empty category does not filter.
	 * Every reading comes off the key, so a rejected index is never fetched from the map.
	 *
	 * @param key      key of the index to test
	 * @param criteria the filters to apply
	 * @return true when the index belongs in the answer
	 */
	private static boolean matches(@Nonnull EntityIndexKey key, @Nonnull IndexBrowseCriteria criteria) {
		final Set<EntityIndexKind> kinds = criteria.indexKinds();
		if (!kinds.isEmpty() && !kinds.contains(EntityCollection.toIndexKind(key.type()))) {
			return false;
		}
		final Set<Scope> scopes = criteria.scopes();
		if (!scopes.isEmpty() && !scopes.contains(key.scope())) {
			return false;
		}
		final Set<String> referenceNames = criteria.referenceNames();
		if (referenceNames.isEmpty()) {
			return true;
		}
		// a `GLOBAL` index is bound to no reference, so it can never satisfy a reference-name filter - that is the
		// accurate reading of "show me the indexes of reference X", not an omission. The null is ruled out before the
		// lookup rather than by it: an immutable `Set.of(...)`, which an embedded caller may well pass, throws from
		// `contains(null)` instead of answering false
		final String referenceName = key.referenceName();
		return referenceName != null && referenceNames.contains(referenceName);
	}

	/**
	 * Renders one index into its descriptor.
	 *
	 * @param key   key identifying the index
	 * @param index the index itself, for its entity count
	 * @return the descriptor
	 */
	@Nonnull
	private static BrowsedIndex describe(@Nonnull EntityIndexKey key, @Nonnull EntityIndex index) {
		return new BrowsedIndex(
			EntityCollection.toIndexKind(key.type()),
			key.scope(),
			key.referenceName(),
			discriminatorPrimaryKeyOf(key),
			index.getAllPrimaryKeys().size()
		);
	}

	/**
	 * Extracts the primary key of the referenced entity an index is bound to.
	 *
	 * Only the per-referenced-entity kinds carry one; the entity-level and per-reference-type kinds cover a whole set
	 * rather than one target, and report null.
	 *
	 * @param key key of the index
	 * @return the referenced entity's primary key, or null when the index is not bound to a single one
	 */
	@Nullable
	private static Integer discriminatorPrimaryKeyOf(@Nonnull EntityIndexKey key) {
		final Serializable discriminator = key.discriminator();
		return discriminator instanceof RepresentativeReferenceKey representativeKey ?
			representativeKey.referenceKey().primaryKey() : null;
	}

	/**
	 * Fetches the index a key was taken from.
	 *
	 * The map is sealed for the duration of the walk, so a key it just yielded must resolve; a miss means the sealed
	 * view was not sealed after all, which is a programming error rather than an empty result to skip over.
	 *
	 * @param indexes the sealed index map
	 * @param key     key yielded by that map's own iterator
	 * @return the index
	 */
	@Nonnull
	private static EntityIndex indexOf(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull EntityIndexKey key
	) {
		final EntityIndex index = indexes.get(key);
		if (index == null) {
			throw new GenericEvitaInternalError(
				"Index `" + key + "` disappeared from a sealed index map while it was being browsed!"
			);
		}
		return index;
	}

	/**
	 * One index that survived filtering, paired with the reading the ordering is computed from.
	 *
	 * Only the key and the count are retained while the walk runs - never the {@link EntityIndex} itself - so the heap
	 * holds no reference to index contents and cannot keep a dropped index alive.
	 *
	 * @param key         key identifying the index
	 * @param entityCount how many entities the index covers
	 */
	private record BrowseCandidate(
		@Nonnull EntityIndexKey key,
		int entityCount
	) {
	}

}
