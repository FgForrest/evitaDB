/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.fetch;


import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.chunk.Slicer;
import io.evitadb.api.requestResponse.chunk.StripTransformer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.chunk.PageTransformerWithSlicer;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * BitmapSlicer is supposed to identify only a small subset of referenced entities and their groups that should
 * be actually fetched / returned in the result taking `filterBy` and `page` / `strip` constraints into an account.
 *
 * Two execution paths are offered, selected by the caller based on whether an `orderBy` is configured:
 *   - {@link #sliceEntityIds} — PK-bitmap fast path used when no orderBy is requested (or the comparator is the
 *     default PK-ascending one); slicing operates directly on the PK bitmap.
 *   - {@link #sliceEntityIdsSorted} — comparator-aware path used when a non-trivial orderBy is in play; it pulls
 *     full reference contracts, sorts them with the configured {@link ReferenceComparator} chain, and slices the
 *     sorted list so the pre-fetch slice agrees with the post-fetch sort done by `EntityDecorator`.
 *
 * The {@link ChunkTransformer} passed to the constructor determines the page/strip mathematics shared by both paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class BitmapSlicer {
	/**
	 * Arrays of source entity primary keys indexed by their scope.
	 */
	@Nonnull private final Map<Scope, int[]> entityPrimaryKey;
	/**
	 * The name of the reference for which the entities are being sliced.
	 * The slicer always work with only single reference.
	 */
	@Nonnull private final String referenceName;
	/**
	 * Function that accepts `referenceName` and `entityPrimaryKey` and returns the formula that contains all
	 * referenced entity ids for the given entity.
	 */
	@Nonnull private final BiFunction<String, Integer, Formula> referencedEntityIdsFormula;
	/**
	 * Function that accepts `referenceName` and `referencedEntityId` and returns the group primary key
	 * for the given referenced entity primary key.
	 */
	@Nonnull private final TriFunction<Integer, String, Integer, IntStream> referencedEntityToGroupIdTranslator;
	/**
	 * Function that accepts the bitmap of referenced entity ids and returns the sliced bitmap to be fetched.
	 */
	@Nonnull private final Function<Bitmap, Bitmap> chunker;
	/**
	 * Function that computes (offset, limit) for a given source size, serving as the single source of truth
	 * for page/strip boundary handling. Both the PK-bitmap fast path ({@link #sliceEntityIds}) and the sorted
	 * comparator-aware path ({@link #sliceEntityIdsSorted}) consume this same function so the sorted slice
	 * produces exactly the set of PKs that the post-fetch sort+chunk in `EntityDecorator` would have kept —
	 * no over-fetch, no under-fetch — independent of whichever {@link ChunkTransformer} was supplied.
	 */
	@Nonnull private final IntFunction<OffsetAndLimit> offsetAndLimitForSize;
	/**
	 * Contains a cache of groups indexed by entity primary key.
	 */
	private Map<Integer, int[]> groupsForEntity = Collections.emptyMap();

	public BitmapSlicer(
		@Nonnull Map<Scope, int[]> entityPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull TriFunction<Integer, String, Integer, IntStream> referencedEntityToGroupIdTranslator,
		@Nonnull ChunkTransformer chunkTransformer
	) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.referenceName = referenceName;
		this.referencedEntityIdsFormula = referencedEntityIdsFormula;
		this.referencedEntityToGroupIdTranslator = referencedEntityToGroupIdTranslator;
		if (chunkTransformer instanceof PageTransformer pageTransformer) {
			this.chunker = (bitmap) -> this.slice(bitmap, pageTransformer.getPage());
			this.offsetAndLimitForSize = size -> pageOffsetAndLimit(pageTransformer.getPage(), size);
		} else if (chunkTransformer instanceof PageTransformerWithSlicer pageTransformerWithSlicer) {
			this.chunker = (bitmap) -> this.slice(
				bitmap, pageTransformerWithSlicer.getPage(), pageTransformerWithSlicer.getSlicer());
			this.offsetAndLimitForSize = size -> pageTransformerWithSlicer.getSlicer().calculateOffsetAndLimit(
				ResultForm.PAGINATED_LIST,
				pageTransformerWithSlicer.getPage().getPageNumber(),
				pageTransformerWithSlicer.getPage().getPageSize(),
				size
			);
		} else if (chunkTransformer instanceof StripTransformer stripTransformer) {
			this.chunker = (bitmap) -> this.slice(bitmap, stripTransformer.getStrip());
			this.offsetAndLimitForSize = size -> stripOffsetAndLimit(stripTransformer.getStrip(), size);
		} else if (chunkTransformer instanceof NoTransformer) {
			this.chunker = Function.identity();
			this.offsetAndLimitForSize = size -> new OffsetAndLimit(0, size, size);
		} else {
			throw new GenericEvitaInternalError("Unsupported chunk transformer: " + chunkTransformer);
		}
	}

	/**
	 * Iterates over all entity primary keys and picks up all references of particular referenceName, filters them
	 * by `referencedEntityIds` and then slices a single chunk by {@link #chunker}. For the sliced
	 * referenced entity ids the set of group ids is gradually built up.
	 *
	 * This method is supposed to identify only a small subset of referenced entities and their groups that should
	 * be actually fetched / returned in the result.
	 *
	 * @param referencedEntityIds global formula of filter-matching referenced entity PKs (filterBy applied)
	 * @param validityMapping     per-source validity gate (multi-source dedup / chunked predicate)
	 * @return all referenced entity ids that match `referencedEntityIds` and are appropriately sliced
	 * on per entity basis by {@link #chunker}
	 */
	@Nonnull
	public Bitmap sliceEntityIds(
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping
	) {
		this.groupsForEntity = CollectionUtils.createHashMap(this.entityPrimaryKey.size());
		return FormulaFactory.or(
			this.entityPrimaryKey
				.values()
				.stream()
				.flatMapToInt(IntStream::of)
				.mapToObj(epk -> {
					final Bitmap filteredReferenceEntityIds = FormulaFactory.and(
						this.referencedEntityIdsFormula.apply(this.referenceName, epk),
						referencedEntityIds,
						validityMapping.getValidReferencedEntitiesFormula(epk)
					).compute();
					final Bitmap chunk = this.chunker.apply(filteredReferenceEntityIds);
					this.groupsForEntity.put(
						epk,
						filteredReferenceEntityIds.stream()
							.mapToObj(
								refId -> this.referencedEntityToGroupIdTranslator.apply(epk, this.referenceName, refId))
							.flatMapToInt(Function.identity())
							.toArray()
					);
					return ReferencedEntityFetcher.toFormula(chunk);
				})
				.toArray(Formula[]::new)
		).compute();
	}

	/**
	 * Order-aware variant of {@link #sliceEntityIds(Formula, ValidEntityToReferenceMapping)} used when an `orderBy`
	 * is configured on the reference content. For each source entity it pulls the actual {@link ReferenceContract}
	 * instances (whose reference attributes are already loaded with the source entity), filters them by the global
	 * filtered referenced PK bitmap and the per-source `validityMapping` (multi-source dedup / chunked predicate),
	 * sorts them by the {@link ReferenceComparator} chain (the same comparator that
	 * {@code EntityDecorator#sortAndFilterSubList} will use post-fetch), then slices the chunk using the configured
	 * page/strip offset+limit. The returned bitmap contains exactly the PKs whose bodies will be the post-fetch
	 * winners — no over-fetch, no under-fetch: the pre-fetch slice matches the post-fetch sort+chunk in
	 * `EntityDecorator`.
	 *
	 * Group accounting here intentionally diverges from {@link #sliceEntityIds}: groups are derived directly from
	 * the {@code referenceContractsAccessor} (the raw source entity), not from the
	 * {@code referencedEntityToGroupIdTranslator} as in the unsorted twin. The translator is backed by an
	 * {@code EntityDecorator} whose chunked-predicate view exposes only references the predicate has materialized;
	 * for references picked by the orderBy-aware slice that view under-reports groups. The contract of
	 * {@link #getGroupIds} therefore stays consistent across both paths: it returns the *full* filtered set of
	 * groups regardless of which slicing path produced the entity bitmap.
	 *
	 * @param referencedEntityIds         global formula of filter-matching referenced entity PKs (filterBy applied)
	 * @param validityMapping             per-source validity formula (same gate the unsorted twin applies)
	 * @param referenceContractsAccessor  per-source-entity accessor for the source entity's reference contracts
	 *                                    (with attributes) so this method can pre-sort by the configured `orderBy`
	 *                                    before fetching
	 * @param comparator                  the orderBy comparator chain (must not be {@link ReferenceComparator#DEFAULT})
	 * @return PKs to actually fetch — exactly the post-fetch sort+chunk winners (no over-fetch, no under-fetch)
	 */
	@Nonnull
	public Bitmap sliceEntityIdsSorted(
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping,
		@Nonnull BiFunction<String, Integer, Collection<ReferenceContract>> referenceContractsAccessor,
		@Nonnull ReferenceComparator comparator
	) {
		this.groupsForEntity = CollectionUtils.createHashMap(this.entityPrimaryKey.size());
		return FormulaFactory.or(
			this.entityPrimaryKey
				.values()
				.stream()
				.flatMapToInt(IntStream::of)
				.mapToObj(epk -> {
					final Bitmap filteredReferenceEntityIds = FormulaFactory.and(
						this.referencedEntityIdsFormula.apply(this.referenceName, epk),
						referencedEntityIds,
						validityMapping.getValidReferencedEntitiesFormula(epk)
					).compute();
					if (filteredReferenceEntityIds.isEmpty()) {
						this.groupsForEntity.put(epk, ArrayUtils.EMPTY_INT_ARRAY);
						return EmptyFormula.INSTANCE;
					}
					// gather the source entity's references whose PK is in the filtered set — see the
					// JavaDoc body for why groups must be derived from these raw references rather than
					// from the chunked-predicate view exposed by referencedEntityToGroupIdTranslator
					final Collection<ReferenceContract> sourceRefs =
						referenceContractsAccessor.apply(this.referenceName, epk);
					final ReferenceContract[] filteredRefs = sourceRefs.stream()
						.filter(r -> filteredReferenceEntityIds.contains(r.getReferencedPrimaryKey()))
						.toArray(ReferenceContract[]::new);
					// group accounting reflects *all* filtered references (independent of slicing), so
					// getGroupIds keeps returning the full set
					this.groupsForEntity.put(epk, groupPksOf(filteredRefs));
					if (filteredRefs.length == 0) {
						return EmptyFormula.INSTANCE;
					}
					sortReferencesByComparatorChain(epk, filteredRefs, comparator);
					final OffsetAndLimit offsetAndLimit = this.offsetAndLimitForSize.apply(filteredRefs.length);
					final int[] sliced = materializeSlicedPks(filteredRefs, offsetAndLimit);
					return sliced.length == 0
						? EmptyFormula.INSTANCE
						: ReferencedEntityFetcher.toFormula(sliced);
				})
				.toArray(Formula[]::new)
		).compute();
	}

	/**
	 * Returns the primary keys of the groups referenced by the supplied references in source order.
	 * References without a group are skipped. Used by the sorted slicing path to derive group
	 * accounting from the same `ReferenceContract` array that drives the slice.
	 */
	@Nonnull
	private static int[] groupPksOf(@Nonnull ReferenceContract[] refs) {
		return Arrays.stream(refs)
			.map(ReferenceContract::getGroup)
			.flatMap(Optional::stream)
			.mapToInt(GroupEntityReference::getPrimaryKeyOrThrowException)
			.toArray();
	}

	/**
	 * Materializes the sliced range `[offset, offset + limit)` of the supplied sorted references into
	 * an `int[]` of their primary keys, clamping both ends to the array bounds. Returns an empty array
	 * when the clamped range is empty.
	 */
	@Nonnull
	private static int[] materializeSlicedPks(
		@Nonnull ReferenceContract[] sorted,
		@Nonnull OffsetAndLimit offsetAndLimit
	) {
		final int from = Math.min(offsetAndLimit.offset(), sorted.length);
		final int to = Math.min(offsetAndLimit.offset() + offsetAndLimit.limit(), sorted.length);
		if (to <= from) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final int[] sliced = new int[to - from];
		for (int i = 0; i < sliced.length; i++) {
			sliced[i] = sorted[from + i].getReferencedPrimaryKey();
		}
		return sliced;
	}

	/**
	 * Sorts the candidates in place by walking the comparator chain. Uses the same delta-snapshot
	 * pattern as {@code EntityDecorator#sortAndFilterSubList} — both call sites must agree because
	 * this method drives the pre-fetch slice and `EntityDecorator` drives the post-fetch sort; any
	 * divergence in window arithmetic would let the slice return PKs the post-fetch sort then
	 * discards (under-fetch) or drop PKs the post-fetch sort would keep (over-fetch).
	 *
	 * Algorithm at each link of the chain: forward `entityPrimaryKey` to EPK-aware links, snapshot
	 * `getNonSortedReferenceCount()`, run `Arrays.sort` over `[start, end)`, then advance `start`
	 * past the just-sorted prefix using the per-pass delta of the snapshot — clamped to
	 * `[0, end - start]` so a comparator whose counter is a cumulative cross-entity accumulator
	 * (notably `EntityNestedQueryComparator`, which never resets `nonSortedReferences` between
	 * source-entity passes) cannot drive `start` past `end` and trip the next `Arrays.sort` with a
	 * backwards range. The shared accumulator behavior is intentional — the comparator's lifecycle
	 * is owned by the caller; clamping on the consumer side keeps it composable.
	 *
	 * @param entityPrimaryKey primary key of the source entity owning the references — forwarded to
	 *                         {@link ReferenceComparator.EntityPrimaryKeyAwareComparator} stages so they can
	 *                         scope their lookups to this entity
	 * @param candidates       references to sort in place (mutated)
	 * @param comparator       head of the comparator chain; subsequent stages are walked via
	 *                         {@link ReferenceComparator#getNextComparator()}
	 */
	private static void sortReferencesByComparatorChain(
		int entityPrimaryKey,
		@Nonnull ReferenceContract[] candidates,
		@Nonnull ReferenceComparator comparator
	) {
		ReferenceComparator current = comparator;
		int start = 0;
		int end = candidates.length;
		while (current != null && end > start) {
			if (current instanceof ReferenceComparator.EntityPrimaryKeyAwareComparator epkAware) {
				epkAware.setEntityPrimaryKey(entityPrimaryKey);
			}
			final int nonSortedBefore = current.getNonSortedReferenceCount();
			Arrays.sort(candidates, start, end, current);
			final int nonSortedAfter = current.getNonSortedReferenceCount();
			final int delta = Math.max(0, Math.min(nonSortedAfter - nonSortedBefore, end - start));
			if (delta == 0) {
				break;
			}
			start = Math.max(end - delta, start);
			current = current.getNextComparator();
		}
	}

	/**
	 * Computes offset+limit for a {@link Page} given the actual source size — same logic that the
	 * bitmap {@link #slice(Bitmap, Page)} uses internally, kept here so the sorted-list path can reuse it
	 * without re-implementing page-boundary handling.
	 *
	 * @param page       requested page (page number + page size)
	 * @param sourceSize size of the input collection being paged
	 * @return offset+limit that, when applied to a collection of {@code sourceSize}, yields the requested page;
	 * if the requested page is past the end the result snaps back to page 1
	 */
	@Nonnull
	private static OffsetAndLimit pageOffsetAndLimit(@Nonnull Page page, int sourceSize) {
		final int pageNumber = page.getPageNumber();
		final int pageSize = page.getPageSize();
		final int realPageNumber = PaginatedList.isRequestedResultBehindLimit(pageNumber, pageSize, sourceSize) ?
			1 : pageNumber;
		final int offset = PaginatedList.getFirstItemNumberForPage(realPageNumber, pageSize);
		return new OffsetAndLimit(offset, pageSize, sourceSize);
	}

	/**
	 * Computes offset+limit for a {@link Strip} given the actual source size, mirroring the bitmap
	 * {@link #slice(Bitmap, Strip)} truncation rules.
	 *
	 * @param strip      requested strip (raw offset + limit)
	 * @param sourceSize size of the input collection being striped
	 * @return offset+limit clamped to the source bounds — when {@code sourceSize == 0} the offset is 0;
	 * otherwise the offset is capped at {@code sourceSize - 1}
	 */
	@Nonnull
	private static OffsetAndLimit stripOffsetAndLimit(@Nonnull Strip strip, int sourceSize) {
		final int offset = sourceSize == 0 ? 0 : Math.min(strip.getOffset(), sourceSize - 1);
		return new OffsetAndLimit(offset, strip.getLimit(), sourceSize);
	}

	/**
	 * Retrieves a Formula object that represents the group IDs associated with the given entity ID
	 * (scoped to the single reference this slicer was constructed for). The group IDs are obtained
	 * from an internal mapping populated by the slicing methods and converted into a Formula for
	 * further processing or computation.
	 *
	 * When the map doesn't contain the groups for the entityId, it is assumed the referenced entities
	 * don't have any group assigned.
	 *
	 * @param entityId the unique identifier of the entity for which group IDs are retrieved, must not be null
	 * @return a Formula object representing the group IDs associated with the specified entity ID
	 */
	@Nonnull
	public Formula getGroupIds(@Nonnull Integer entityId) {
		return ReferencedEntityFetcher.toFormula(this.groupsForEntity.get(entityId));
	}

	/**
	 * Creates a subset of the provided bitmap by slicing it based on the specified page number and page size
	 * defined in the provided page object. If the page number or size exceeds the bounds of the bitmap,
	 * adjustments are made to fit within the bitmap size.
	 *
	 * @param primaryKeys the bitmap containing the full set of record IDs to be sliced
	 * @param page        the page object defining the page number and size for slicing the bitmap
	 * @return a new bitmap containing the sliced subset of record IDs
	 */
	@Nonnull
	public Bitmap slice(@Nonnull Bitmap primaryKeys, @Nonnull Page page) {
		if (primaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final OffsetAndLimit offsetAndLimit = pageOffsetAndLimit(page, primaryKeys.size());
		return new ArrayBitmap(
			primaryKeys.getRange(
				offsetAndLimit.offset(),
				Math.min(offsetAndLimit.offset() + offsetAndLimit.limit(), primaryKeys.size())
			)
		);
	}

	/**
	 * Creates a subset of the provided bitmap by slicing it based on the offset and limit computed by the
	 * supplied {@link Slicer} for the given page. The slicer drives the actual offset+limit math (which
	 * may differ from the plain page-number/page-size mapping). If the resulting range exceeds the bounds
	 * of the bitmap, the upper end is truncated to fit within the bitmap size.
	 *
	 * @param primaryKeys the bitmap containing the full set of record IDs to be sliced
	 * @param page        the page object defining the page number and size for slicing the bitmap
	 * @param slicer      the slicer used to compute the actual offset and limit for the page
	 * @return a new bitmap containing the sliced subset of record IDs
	 */
	@Nonnull
	public Bitmap slice(@Nonnull Bitmap primaryKeys, @Nonnull Page page, @Nonnull Slicer slicer) {
		final OffsetAndLimit offsetAndLimit = slicer.calculateOffsetAndLimit(
			ResultForm.PAGINATED_LIST, page.getPageNumber(), page.getPageSize(), primaryKeys.size()
		);
		return primaryKeys.isEmpty() ?
			EmptyBitmap.INSTANCE :
			new ArrayBitmap(
				primaryKeys.getRange(
					offsetAndLimit.offset(),
					Math.min(offsetAndLimit.offset() + offsetAndLimit.limit(), primaryKeys.size())
				)
			);
	}

	/**
	 * Creates a subset of the provided bitmap by slicing it based on the specified offset and limit
	 * defined in the provided strip object. If the offset or limit exceeds the bounds of the bitmap,
	 * the values are truncated to fit within the bitmap size.
	 *
	 * @param primaryKeys the bitmap containing the full set of record IDs to be sliced
	 * @param strip       the strip object defining the offset and limit for slicing the bitmap
	 * @return a new bitmap containing the subset of the original bitmap as defined by the strip
	 */
	@Nonnull
	public Bitmap slice(@Nonnull Bitmap primaryKeys, @Nonnull Strip strip) {
		if (primaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final OffsetAndLimit offsetAndLimit = stripOffsetAndLimit(strip, primaryKeys.size());
		return new ArrayBitmap(
			primaryKeys.getRange(
				offsetAndLimit.offset(),
				Math.min(offsetAndLimit.offset() + offsetAndLimit.limit(), primaryKeys.size())
			)
		);
	}

}
