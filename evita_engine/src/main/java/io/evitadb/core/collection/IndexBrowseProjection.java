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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexActivity;

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
	 * Every ranked key read descending as one comparator: the reading frozen on the candidate, largest first, ties
	 * broken by the index key.
	 *
	 * One comparator serves all three keys because the reading they differ in has already been resolved - each
	 * candidate carries the value its own key ranks by, taken once during the walk - so what is left to compare is the
	 * same `long` whichever counter it came from.
	 *
	 * The tiebreaker is not a nicety. Every one of these orders is tie-dominated: entity counts because most
	 * per-referenced-entity indexes cover a handful of entities each, activity counts because most indexes are never
	 * touched at all - and an unstable order silently corrupts pagination, repeating some indexes across pages while
	 * omitting others. {@link EntityIndexKey} already compares by type, then scope, then discriminator, which is a
	 * total order over the keys of one collection and therefore leaves no ties for chance to resolve.
	 */
	private static final Comparator<BrowseCandidate> RANKED_DESC =
		Comparator.comparingLong(BrowseCandidate::rankedValue).reversed()
			.thenComparing(BrowseCandidate::key);
	/**
	 * Every ranked key read ascending as one comparator: smallest first, ties broken by the same key ordering
	 * {@link #RANKED_DESC} uses.
	 *
	 * The tiebreaker running ascending under a descending rank as well is deliberate rather than an oversight: it is
	 * what a page boundary drawn inside a tie block relies on, and it has no direction of its own to reverse. The two
	 * activity counters read ascending need it most, because the block of never-touched indexes they lead with is
	 * where nearly every page boundary falls.
	 */
	private static final Comparator<BrowseCandidate> RANKED_ASC =
		Comparator.comparingLong(BrowseCandidate::rankedValue)
			.thenComparing(BrowseCandidate::key);

	private IndexBrowseProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Selects, orders and pages the collection's indexes.
	 *
	 * @param entityType     name of the collection whose indexes these are, carried onto every row so that a client
	 *                       merging this page with a catalog-level one still has the other half of each row's identity
	 * @param indexes        the collection's index map, sealed by the caller so that the count and the page contents
	 *                       cannot come from two different states
	 * @param criteria       which indexes to select, in what order, and which page to return
	 * @param catalogVersion version of the catalog the sealed map was taken from, reported so a caller can tell that
	 *                       two pages of one browse describe two different index sets
	 * @return the requested page and the total number of matches behind it
	 */
	@Nonnull
	static IndexBrowseResult browse(
		@Nonnull String entityType,
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria,
		long catalogVersion
	) {
		// both factors are client-supplied and `pageNumber` is unbounded in `MAP_ORDER` - the only ordering that can
		// reach this, since every ranked window is capped in the criteria - so an int multiplication could wrap and
		// silently serve some unrelated window as the one asked for; the offset is therefore computed in long
		final long offset = (long) (criteria.pageNumber() - 1) * criteria.pageSize();
		return switch (criteria.ordering()) {
			case MAP_ORDER -> {
				final List<BrowsedIndex> page = new ArrayList<>(criteria.pageSize());
				final int matchCount = collectInMapOrder(entityType, indexes, criteria, offset, page);
				yield toResult(criteria, catalogVersion, matchCount, page);
			}
			// every other key ranks its candidates, and a browse differs only in which reading is ranked and in which
			// direction it is read - both resolved once here, so one walk and one page cut serve every combination
			case ENTITY_COUNT, QUERY_COUNT, UPDATE_COUNT -> {
				final RankedCounter rankedCounter = rankedCounterOf(criteria.ordering());
				final Comparator<BrowseCandidate> order = comparatorOf(criteria.direction());
				final PriorityQueue<BrowseCandidate> heap = new PriorityQueue<>(order.reversed());
				final int matchCount = collectRanked(indexes, criteria, offset, heap, order, rankedCounter);
				yield toResult(
					criteria, catalogVersion, matchCount,
					cutPage(entityType, heap, criteria, offset, order, rankedCounter)
				);
			}
		};
	}

	/**
	 * Walks the map in its own order, counting every match and materialising a descriptor only inside the window.
	 *
	 * @param entityType name of the collection whose indexes these are
	 * @param indexes  the sealed index map to walk
	 * @param criteria the selection to apply
	 * @param offset   how many matches precede the requested page
	 * @param page     accumulator the window's descriptors are appended to
	 * @return how many indexes matched in total
	 */
	private static int collectInMapOrder(
		@Nonnull String entityType,
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
				// one fetch serves every reading - the identity the row is addressed by, the count it reports and the
				// activity holder it reads its five activity readings off. Nothing is ranked here, so all five are
				// taken at the same moment and none of them has a position to stay consistent with
				final EntityIndex index = indexOf(indexes, key);
				final IndexActivity activity = index.getActivity();
				page.add(
					describe(
						entityType, key, index.getPrimaryKey(), index.getAllPrimaryKeys().size(),
						activity == null ? 0L : activity.getQueryCount(),
						activity == null ? 0L : activity.getUpdateCount(),
						activity
					)
				);
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
	 * documents every ranked ordering as a top-N access pattern.
	 *
	 * **The ranked reading is taken here and nowhere else.** A comparator that read a live {@link IndexActivity}
	 * instead would be comparing a key that moves while it is being used: a {@link PriorityQueue} does not reheapify
	 * when the priority of an element it already holds changes, two comparisons of one pair can disagree, and the sort
	 * in {@link #cutPage} is entitled to throw on the contract violation that follows. Freezing the value on the
	 * candidate makes the comparator a pure function of immutable data, and the ranking merely *stale* - which is what
	 * the ordering's own documentation promises a client.
	 *
	 * @param indexes       the sealed index map to walk
	 * @param criteria      the selection to apply
	 * @param offset        how many matches precede the requested page
	 * @param heap          accumulator retaining the best candidates
	 * @param order         the requested order, used to decide which retained candidate an arrival evicts
	 * @param rankedCounter the reading the order ranks by, resolved once by the caller
	 * @return how many indexes matched in total
	 */
	private static int collectRanked(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria,
		long offset,
		@Nonnull PriorityQueue<BrowseCandidate> heap,
		@Nonnull Comparator<BrowseCandidate> order,
		@Nonnull RankedCounter rankedCounter
	) {
		final long retained = offset + criteria.pageSize();
		int matched = 0;
		for (final EntityIndexKey key : indexes.keySet()) {
			if (!matches(key, criteria)) {
				continue;
			}
			matched++;
			// the readings are taken here rather than in the page cut, because ordering needs one for every match -
			// the entity count is an O(1) cardinality of the index's primary-key bitmap, never a walk of the index
			// contents, and an activity count is a single volatile field read. The identity comes off the same fetch,
			// so retaining it costs a comparison-free int rather than a second lookup
			final EntityIndex index = indexOf(indexes, key);
			final IndexActivity activity = index.getActivity();
			final int entityCount = index.getAllPrimaryKeys().size();
			// a server that does not track usage statistics ranks every index at zero on the two activity keys, which
			// leaves the key tiebreaker to order the page - a stable, meaningless-but-not-wrong order for a reading
			// every row of the page declares itself not to have
			final long rankedValue = switch (rankedCounter) {
				case ENTITY_COUNT -> entityCount;
				case QUERY_COUNT -> activity == null ? 0L : activity.getQueryCount();
				case UPDATE_COUNT -> activity == null ? 0L : activity.getUpdateCount();
			};
			final BrowseCandidate candidate = new BrowseCandidate(
				key, index.getPrimaryKey(), entityCount, rankedValue, activity
			);
			if (heap.size() < retained) {
				heap.offer(candidate);
			} else if (order.compare(candidate, heap.peek()) < 0) {
				heap.poll();
				heap.offer(candidate);
			}
		}
		return matched;
	}

	/**
	 * Drains the heap into the requested order and cuts the page out of it.
	 *
	 * @param entityType    name of the collection whose indexes these are
	 * @param heap          the retained candidates, in no useful order of their own
	 * @param criteria      the page to cut
	 * @param offset        how many matches precede the requested page
	 * @param order         the requested order to drain the heap into
	 * @param rankedCounter the reading that order ranks by, which is the one column the row reports from the walk
	 * @return the page's descriptors, in the requested order
	 */
	@Nonnull
	private static List<BrowsedIndex> cutPage(
		@Nonnull String entityType,
		@Nonnull PriorityQueue<BrowseCandidate> heap,
		@Nonnull IndexBrowseCriteria criteria,
		long offset,
		@Nonnull Comparator<BrowseCandidate> order,
		@Nonnull RankedCounter rankedCounter
	) {
		final List<BrowseCandidate> ordered = new ArrayList<>(heap);
		ordered.sort(order);

		// the heap retained everything up to the end of the requested page, so the page starts at `offset` within it
		// - and `offset` is safe to narrow here because the heap never holds more than `offset + pageSize` entries
		final int from = (int) Math.min(offset, ordered.size());
		final int to = Math.min(from + criteria.pageSize(), ordered.size());
		final List<BrowsedIndex> page = new ArrayList<>(to - from);
		for (int i = from; i < to; i++) {
			// the ranked count carried from the walk, not a fresh reading: it is the one this row was *ordered* by, and
			// an index whose traffic moved between the walk and here would otherwise be reported with a count that
			// contradicts its own position in the page. The asymmetry with the readings below is deliberate - only the
			// ranked counter has a position to stay consistent with, so the others are taken fresh, where newer is
			// simply better
			final BrowseCandidate candidate = ordered.get(i);
			final IndexActivity activity = candidate.activity();
			// with no holder there is nothing to report and nothing to stay consistent with. The frozen ranked value
			// must NOT be substituted here: under `ENTITY_COUNT` it is the entity count, which is real even with the
			// counters off, so reporting it would invent traffic out of a cardinality
			final long queryCount;
			final long updateCount;
			if (activity == null) {
				queryCount = 0L;
				updateCount = 0L;
			} else {
				queryCount = rankedCounter == RankedCounter.QUERY_COUNT ?
					candidate.rankedValue() : activity.getQueryCount();
				updateCount = rankedCounter == RankedCounter.UPDATE_COUNT ?
					candidate.rankedValue() : activity.getUpdateCount();
			}
			page.add(
				describe(
					entityType, candidate.key(), candidate.indexPrimaryKey(), candidate.entityCount(),
					queryCount, updateCount, activity
				)
			);
		}
		return page;
	}

	/**
	 * Which reading a ranked ordering key places its candidates by, resolved once per browse rather than per candidate.
	 *
	 * @param ordering the ordering key to resolve, which must be one that ranks
	 * @return the reading it ranks by
	 */
	@Nonnull
	private static RankedCounter rankedCounterOf(@Nonnull IndexBrowseOrdering ordering) {
		return switch (ordering) {
			case ENTITY_COUNT -> RankedCounter.ENTITY_COUNT;
			case QUERY_COUNT -> RankedCounter.QUERY_COUNT;
			case UPDATE_COUNT -> RankedCounter.UPDATE_COUNT;
			case MAP_ORDER -> throw new GenericEvitaInternalError(
				"Ordering `" + ordering + "` ranks nothing and must never reach the ranked page build!"
			);
		};
	}

	/**
	 * The comparator a ranked browse imposes, which is its direction alone - the reading has already been resolved onto
	 * the candidates by {@link #rankedCounterOf(IndexBrowseOrdering)}, so what is left is which end of the frozen
	 * `long` the page is cut from.
	 *
	 * @param direction the direction the page is ordered in
	 * @return its comparator
	 */
	@Nonnull
	private static Comparator<BrowseCandidate> comparatorOf(@Nonnull OrderDirection direction) {
		return switch (direction) {
			case DESC -> RANKED_DESC;
			case ASC -> RANKED_ASC;
		};
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
		if (!matchesType(key, criteria.indexTypes())) {
			return false;
		}
		if (!matchesScope(key, criteria.scopes())) {
			return false;
		}
		return matchesReferenceName(key, criteria.referenceNames());
	}

	/**
	 * Tells whether an index's type passes the type filter.
	 *
	 * @param key   key of the index to test
	 * @param types the requested types, an empty set standing for "every type"
	 * @return true when the index belongs in the answer
	 */
	private static boolean matchesType(@Nonnull EntityIndexKey key, @Nonnull Set<EntityIndexType> types) {
		return types.isEmpty() || types.contains(key.type());
	}

	/**
	 * Tells whether an index's scope passes the scope filter.
	 *
	 * @param key    key of the index to test
	 * @param scopes the requested scopes, an empty set standing for "every scope"
	 * @return true when the index belongs in the answer
	 */
	private static boolean matchesScope(@Nonnull EntityIndexKey key, @Nonnull Set<Scope> scopes) {
		return scopes.isEmpty() || scopes.contains(key.scope());
	}

	/**
	 * Tells whether an index's reference name passes the reference-name filter.
	 *
	 * A `GLOBAL` index is bound to no reference, so it can never satisfy a reference-name filter - that is the
	 * accurate reading of "show me the indexes of reference X", not an omission. The null is ruled out before the
	 * lookup rather than by it: an immutable `Set.of(...)`, which an embedded caller may well pass, throws from
	 * `contains(null)` instead of answering false.
	 *
	 * @param key            key of the index to test
	 * @param referenceNames the requested reference names, an empty set standing for "every reference"
	 * @return true when the index belongs in the answer
	 */
	private static boolean matchesReferenceName(@Nonnull EntityIndexKey key, @Nonnull Set<String> referenceNames) {
		if (referenceNames.isEmpty()) {
			return true;
		}
		final String referenceName = key.referenceName();
		return referenceName != null && referenceNames.contains(referenceName);
	}

	/**
	 * Renders one index into its descriptor.
	 *
	 * The two counts are handed in rather than read off the holder here, because a ranked page reports the one it was
	 * ranked by from the reading that placed the row - see {@link #cutPage}.
	 *
	 * @param entityType      name of the collection whose index this is
	 * @param key             key identifying the index
	 * @param indexPrimaryKey identity of the index, which is what the descriptor is addressed by
	 * @param entityCount     how many entities the index covers
	 * @param queryCount      how many executed query plans have chosen the index
	 * @param updateCount     how many entity mutations have acquired the index for modification
	 * @param activity        the index's activity holder, read here rather than during the walk - three `O(1)` field
	 *                        reads that only the rows actually on the page pay for. Null when the server does not
	 *                        track usage statistics, which is what makes the row report itself unmeasured
	 * @return the descriptor
	 */
	@Nonnull
	private static BrowsedIndex describe(
		@Nonnull String entityType,
		@Nonnull EntityIndexKey key,
		int indexPrimaryKey,
		int entityCount,
		long queryCount,
		long updateCount,
		@Nullable IndexActivity activity
	) {
		return new BrowsedIndex(
			entityType,
			indexPrimaryKey,
			key.type(),
			key.scope(),
			renderDiscriminator(key),
			key.referenceName(),
			discriminatorPrimaryKeyOf(key),
			entityCount,
			queryCount,
			updateCount,
			activity == null ? null : activity.getLastQueriedAt(),
			activity == null ? null : activity.getLastUpdatedAt(),
			activity == null ? null : activity.getObservedSince(),
			activity != null
		);
	}

	/**
	 * Renders the whole discriminator, rather than the two readable parts {@link #describe} projects out of it.
	 *
	 * A {@link RepresentativeReferenceKey} also carries the representative attribute values that tell two indexes of
	 * one reference and one target apart, and those participate in its equality and ordering. Rendering only the
	 * reference name and the primary key would make such a pair read identically, leaving an operator two rows that
	 * differ only in an opaque integer and no way to see what distinguishes them.
	 *
	 * **Why this does not delegate to `toString`, even though the row's identity is its index primary key.** The
	 * discriminator is what a human reads to tell one row from another, and `RepresentativeReferenceKey`'s own
	 * rendering is not injective: it joins the representative values with an unescaped `", "` and prints a null one as
	 * the literal `NULL`, so `["a", "b, c"]` and `["a, b", "c"]` collapse to the same text, as do `[null]` and
	 * `["NULL"]`. Two genuinely distinct indexes would then be indistinguishable on screen - a weaker failure than the
	 * silent deduplication this guarded against when the triplet was the identity, but the operator still cannot act on
	 * a page whose rows read alike. Each part is therefore length-prefixed here, which no value can forge, and
	 * `toString` keeps its readable shape for logging.
	 *
	 * Shared with {@link IndexDetailProjection}, so the discriminator an operator reads on a browse row and the one a
	 * drill-down echoes back are produced by one renderer rather than two that could drift apart.
	 *
	 * @param key key of the index
	 * @return the rendered discriminator, or null for an index that carries none
	 */
	@Nullable
	static String renderDiscriminator(@Nonnull EntityIndexKey key) {
		final Serializable discriminator = key.discriminator();
		if (discriminator == null) {
			return null;
		}
		// the schema-bounded kinds are discriminated by the reference name alone, and distinct names already render
		// distinctly - there is nothing to disambiguate
		if (discriminator instanceof String referenceName) {
			return referenceName;
		}
		if (discriminator instanceof RepresentativeReferenceKey representativeKey) {
			final ReferenceKey referenceKey = representativeKey.referenceKey();
			final Serializable[] values = representativeKey.representativeAttributeValues();
			final StringBuilder result = new StringBuilder(32 + values.length * 16);
			appendLengthPrefixed(result, referenceKey.referenceName());
			result.append('/').append(referenceKey.primaryKey());
			for (final Serializable value : values) {
				result.append('/');
				appendLengthPrefixed(result, value == null ? null : value.toString());
			}
			return result.toString();
		}
		throw new GenericEvitaInternalError(
			"Index key `" + key + "` carries a discriminator of unexpected type `" +
				discriminator.getClass().getName() + "`!"
		);
	}

	/**
	 * Appends one part as its character count, a colon, and the part itself - an encoding no value can forge, since
	 * the reader knows exactly how many characters to consume before the next separator.
	 *
	 * @param result accumulator to append to
	 * @param part   the part to encode, or null for an absent one
	 */
	private static void appendLengthPrefixed(@Nonnull StringBuilder result, @Nullable String part) {
		// a real string can never present a negative length, so this cannot collide with the literal text "NULL" or
		// with any other value
		result.append(part == null ? -1 : part.length()).append(':');
		if (part != null) {
			result.append(part);
		}
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
	 * Only the key, two ints and the activity holder are retained while the walk runs - never the {@link EntityIndex}
	 * itself - so the heap holds no reference to index contents and cannot keep a dropped index alive. The identity is
	 * carried as its primary key for exactly that reason: an int keeps nothing alive, and an {@link IndexActivity}
	 * holds no back-reference to the index it belongs to.
	 *
	 * @param key             key identifying the index
	 * @param indexPrimaryKey identity of the index, carried through to the descriptor
	 * @param entityCount     how many entities the index covers
	 * @param rankedValue     the reading this candidate is ranked by, frozen at the moment the walk reached it - the
	 *                        entity count above for {@link IndexBrowseOrdering#ENTITY_COUNT}, an activity counter for
	 *                        the two keys that rank by one. It is duplicated rather than derived on demand precisely
	 *                        so that no comparison can ever reach a value that moves
	 * @param activity        the index's activity holder, read only if this candidate makes it onto the page, and
	 *                        null when the server does not track usage statistics
	 */
	private record BrowseCandidate(
		@Nonnull EntityIndexKey key,
		int indexPrimaryKey,
		int entityCount,
		long rankedValue,
		@Nullable IndexActivity activity
	) {
	}

	/**
	 * The reading a ranked ordering key places its candidates by.
	 *
	 * It carries the three ranked {@link IndexBrowseOrdering} keys over one for one, minus the one key that ranks
	 * nothing - which is the whole of its point: the domain it names cannot express
	 * {@link IndexBrowseOrdering#MAP_ORDER}, so the page cut switches over it without an arm for a case that must never
	 * arrive. Nothing downstream of the walk needs to know which counter a candidate's
	 * {@link BrowseCandidate#rankedValue()} came from, except the page cut - which has to report it back in the right
	 * column.
	 */
	private enum RankedCounter {

		/**
		 * The cardinality of the index's primary-key bitmap.
		 */
		ENTITY_COUNT,
		/**
		 * {@link IndexActivity#getQueryCount()}.
		 */
		QUERY_COUNT,
		/**
		 * {@link IndexActivity#getUpdateCount()}.
		 */
		UPDATE_COUNT

	}

}
