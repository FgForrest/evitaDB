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


import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntIntHashMap;
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
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
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
 *     full reference contracts, sorts them with the {@link ReferenceComparator} chain and each owner's scope,
 *     and slices the sorted rows using the same order as `EntityDecorator`.
 *
 * The {@link ChunkTransformer} passed to the constructor determines the page/strip mathematics shared by both paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class BitmapSlicer {
	/**
	 * Group state of a reference that holds no group.
	 */
	private static final int NO_GROUP = Integer.MIN_VALUE;
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
	 * TRUE when the chunk transformer slices anything at all - with {@link NoTransformer} every filtered key is
	 * fetched, so there is nothing to slice out of the rows either.
	 */
	private final boolean chunked;
	/**
	 * Contains a cache of groups indexed by entity primary key.
	 */
	private Map<Integer, int[]> groupsForEntity = Collections.emptyMap();
	/**
	 * The group state - the single group, or {@link #NO_GROUP} - each referenced entity was first recorded with,
	 * indexed by the referenced entity primary key. NULL unless the group assignments are tracked, which only the
	 * ordering by a group property needs - see {@link #isGroupAssignmentVarying()}.
	 */
	@Nullable private final IntIntHashMap groupStateByReferencedEntity;
	/**
	 * TRUE once a referenced entity was recorded with two different group states - see
	 * {@link #isGroupAssignmentVarying()}.
	 */
	private boolean groupAssignmentVarying;

	/**
	 * Creates a slicer.
	 *
	 * @param entityPrimaryKey                    source entity primary keys indexed by their scope
	 * @param referenceName                       the name of the sliced reference
	 * @param referencedEntityIdsFormula          returns all referenced entity ids of a source entity
	 * @param referencedEntityToGroupIdTranslator returns the groups of a referenced entity of a source entity
	 * @param chunkTransformer                    the page / strip mathematics of the slice
	 * @param trackGroupAssignments               TRUE to track while recording the groups whether the group of
	 *                                            a referenced entity varies between its references - see
	 *                                            {@link #isGroupAssignmentVarying()}
	 */
	public BitmapSlicer(
		@Nonnull Map<Scope, int[]> entityPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull TriFunction<Integer, String, Integer, IntStream> referencedEntityToGroupIdTranslator,
		@Nonnull ChunkTransformer chunkTransformer,
		boolean trackGroupAssignments
	) {
		this.groupStateByReferencedEntity = trackGroupAssignments ? new IntIntHashMap() : null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.referenceName = referenceName;
		this.referencedEntityIdsFormula = referencedEntityIdsFormula;
		this.referencedEntityToGroupIdTranslator = referencedEntityToGroupIdTranslator;
		this.chunked = !(chunkTransformer instanceof NoTransformer);
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
	 * The decorator pages the selected rows ordered by their referenced primary key, so slicing the distinct keys
	 * matches it only while every key stands for one row. When several selected rows of a reference allowing
	 * duplicates may share a key, the chunk is sliced out of the rows instead - see {@link #sliceRows}.
	 *
	 * @param referencedEntityIds global formula of filter-matching referenced entity PKs (filterBy applied)
	 * @param validityMapping     per-source validity gate (multi-source dedup / chunked predicate)
	 * @param rowsOfDuplicates    per-source-entity accessor of the reference rows, when several selected rows may share
	 *                            a referenced key - a reference allowing duplicates whose validity mapping is filled
	 *                            row by row; NULL when every selected key stands for a single row
	 * @return all referenced entity ids that match `referencedEntityIds` and are appropriately sliced
	 * on per entity basis by {@link #chunker}
	 */
	@Nonnull
	public Bitmap sliceEntityIds(
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping,
		@Nullable BiFunction<String, Integer, Collection<ReferenceContract>> rowsOfDuplicates
	) {
		this.groupsForEntity = CollectionUtils.createHashMap(this.entityPrimaryKey.size());
		return FormulaFactory.or(
			this.entityPrimaryKey
				.values()
				.stream()
				.flatMapToInt(IntStream::of)
				.mapToObj(epk -> {
					final Bitmap filteredReferenceEntityIds = filterReferencedEntityIds(
						epk, referencedEntityIds, validityMapping
					);
					final Bitmap chunk = rowsOfDuplicates == null || !this.chunked ?
						this.chunker.apply(filteredReferenceEntityIds) :
						sliceRows(epk, filteredReferenceEntityIds, validityMapping, rowsOfDuplicates);
					recordGroupIds(epk, filteredReferenceEntityIds);
					return ReferencedEntityFetcher.toFormula(chunk);
				})
				.toArray(Formula[]::new)
		).compute();
	}

	/**
	 * Slices the chunk of the source entity out of its selected rows rather than out of its referenced keys. The
	 * decorator pages the rows it selects, ordered by their referenced primary key, and several rows of a reference
	 * allowing duplicates may hold one key - the keys a page needs are those of the rows at its positions, which a
	 * slice of the distinct keys misses or takes from another page. Which of the rows sharing a key comes first makes
	 * no difference to the keys the page holds.
	 *
	 * @param epk                        primary key of the source entity
	 * @param filteredReferenceEntityIds the filtered referenced entity primary keys of the source entity
	 * @param validityMapping            the mapping filled row by row, selecting the rows the decorator keeps
	 * @param rowsAccessor               per-source-entity accessor of the reference rows
	 * @return the referenced primary keys of the rows of the chunk
	 */
	@Nonnull
	private Bitmap sliceRows(
		int epk,
		@Nonnull Bitmap filteredReferenceEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping,
		@Nonnull BiFunction<String, Integer, Collection<ReferenceContract>> rowsAccessor
	) {
		if (filteredReferenceEntityIds.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final Collection<ReferenceContract> rows = rowsAccessor.apply(this.referenceName, epk);
		final int[] keysOfSelectedRows = new int[rows.size()];
		int selectedRowCount = 0;
		for (ReferenceContract row : rows) {
			final int referencedPrimaryKey = row.getReferencedPrimaryKey();
			if (
				filteredReferenceEntityIds.contains(referencedPrimaryKey) &&
					validityMapping.isReferenceSelected(epk, row)
			) {
				keysOfSelectedRows[selectedRowCount++] = referencedPrimaryKey;
			}
		}
		Arrays.sort(keysOfSelectedRows, 0, selectedRowCount);
		final OffsetAndLimit offsetAndLimit = this.offsetAndLimitForSize.apply(selectedRowCount);
		final int from = Math.min(offsetAndLimit.offset(), selectedRowCount);
		final int to = Math.min(offsetAndLimit.offset() + offsetAndLimit.limit(), selectedRowCount);
		if (to <= from) {
			return EmptyBitmap.INSTANCE;
		}
		// the rows of the chunk are sorted by their key, so the distinct keys are written in one pass
		final int[] keysOfChunk = new int[to - from];
		int keyCount = 0;
		for (int i = from; i < to; i++) {
			if (keyCount == 0 || keysOfChunk[keyCount - 1] != keysOfSelectedRows[i]) {
				keysOfChunk[keyCount++] = keysOfSelectedRows[i];
			}
		}
		return new ArrayBitmap(keyCount == keysOfChunk.length ? keysOfChunk : Arrays.copyOf(keysOfChunk, keyCount));
	}

	/**
	 * Records the groups of the filtered references of every source entity without slicing anything, for the case
	 * when no referenced entity body is fetched and so neither {@link #sliceEntityIds} nor
	 * {@link #sliceEntityIdsSorted} runs. The group bodies and the ordering by a group property still read
	 * {@link #getGroupIds}, which would otherwise report no group at all. The recorded set is the one
	 * {@link #sliceEntityIds} records - the groups of all filtered references, regardless of the page or strip.
	 *
	 * @param referencedEntityIds global formula of filter-matching referenced entity PKs (filterBy applied)
	 * @param validityMapping     per-source validity gate (multi-source dedup / chunked predicate)
	 */
	public void collectGroupIds(
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping
	) {
		int sourceEntityCount = 0;
		for (int[] primaryKeys : this.entityPrimaryKey.values()) {
			sourceEntityCount += primaryKeys.length;
		}
		this.groupsForEntity = CollectionUtils.createHashMap(sourceEntityCount);
		for (int[] primaryKeys : this.entityPrimaryKey.values()) {
			for (int epk : primaryKeys) {
				recordGroupIds(epk, filterReferencedEntityIds(epk, referencedEntityIds, validityMapping));
			}
		}
	}

	/**
	 * Returns the primary keys of the entities the source entity references that match both `referencedEntityIds`
	 * and the per-source `validityMapping`.
	 *
	 * @param epk                 primary key of the source entity
	 * @param referencedEntityIds global formula of filter-matching referenced entity PKs (filterBy applied)
	 * @param validityMapping     per-source validity gate (multi-source dedup / chunked predicate)
	 * @return the filtered referenced entity primary keys of the source entity
	 */
	@Nonnull
	private Bitmap filterReferencedEntityIds(
		int epk,
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping
	) {
		return FormulaFactory.and(
			this.referencedEntityIdsFormula.apply(this.referenceName, epk),
			referencedEntityIds,
			validityMapping.getValidReferencedEntitiesFormula(epk)
		).compute();
	}

	/**
	 * Records the groups of the passed referenced entities of the source entity, so that {@link #getGroupIds}
	 * returns them.
	 *
	 * @param epk                        primary key of the source entity
	 * @param filteredReferenceEntityIds the filtered referenced entity primary keys of the source entity
	 */
	private void recordGroupIds(int epk, @Nonnull Bitmap filteredReferenceEntityIds) {
		if (this.groupStateByReferencedEntity == null) {
			this.groupsForEntity.put(
				epk,
				filteredReferenceEntityIds.stream()
					.mapToObj(
						refId -> this.referencedEntityToGroupIdTranslator.apply(epk, this.referenceName, refId))
					.flatMapToInt(Function.identity())
					.toArray()
			);
		} else {
			this.groupsForEntity.put(epk, recordAndTrackGroupIds(epk, filteredReferenceEntityIds));
		}
	}

	/**
	 * Variant of {@link #recordGroupIds(int, Bitmap)} that also tracks whether the group of a referenced entity
	 * varies between its references: it compares the group state of each reference - its single group, or
	 * {@link #NO_GROUP} - with the state the referenced entity was first recorded with, one primitive map probe per
	 * reference, and stops comparing once a difference was found.
	 *
	 * @param epk                        primary key of the source entity
	 * @param filteredReferenceEntityIds the filtered referenced entity primary keys of the source entity
	 * @return the groups of the passed referenced entities
	 */
	@Nonnull
	private int[] recordAndTrackGroupIds(int epk, @Nonnull Bitmap filteredReferenceEntityIds) {
		final IntIntHashMap groupStates = Objects.requireNonNull(this.groupStateByReferencedEntity);
		final IntArrayList groupIds = new IntArrayList(filteredReferenceEntityIds.size());
		final OfInt referencedEntityIt = filteredReferenceEntityIds.iterator();
		while (referencedEntityIt.hasNext()) {
			final int refId = referencedEntityIt.nextInt();
			final OfInt groupIt = this.referencedEntityToGroupIdTranslator.apply(epk, this.referenceName, refId)
				.iterator();
			int groupState = NO_GROUP;
			while (groupIt.hasNext()) {
				final int groupId = groupIt.nextInt();
				groupIds.add(groupId);
				if (groupState == NO_GROUP) {
					groupState = groupId;
				} else if (groupState != groupId) {
					// a single source entity holds the referenced entity in two groups
					this.groupAssignmentVarying = true;
				}
			}
			if (!this.groupAssignmentVarying) {
				final int index = groupStates.indexOf(refId);
				if (groupStates.indexExists(index)) {
					this.groupAssignmentVarying = groupStates.indexGet(index) != groupState;
				} else {
					groupStates.indexInsert(index, refId, groupState);
				}
			}
		}
		return groupIds.toArray();
	}

	/**
	 * Returns TRUE when the group of some referenced entity differs between the references recorded so far -
	 * a source entity holds it in another group than another source entity does, holds it without a group while
	 * another one holds it in a group, or holds it in two groups at once. The ordering by a group property then
	 * cannot place a referenced entity by its primary key alone. Always FALSE unless the slicer was created to track
	 * the group assignments.
	 *
	 * @return TRUE when the group of a referenced entity varies between its references
	 */
	public boolean isGroupAssignmentVarying() {
		return this.groupAssignmentVarying;
	}

	/**
	 * Order-aware variant of {@link #sliceEntityIds(Formula, ValidEntityToReferenceMapping, BiFunction)} used when an
	 * `orderBy` is configured on the reference content. For each source entity it pulls the actual
	 * {@link ReferenceContract} instances (whose reference attributes are already loaded with the source entity),
	 * filters them by the global filtered referenced PK bitmap and the per-source `validityMapping` (multi-source
	 * dedup / chunked predicate),
	 * sorts them by the {@link ReferenceComparator} chain (the same comparator that
	 * {@code EntityDecorator#sortAndFilterSubList} will use post-fetch), then slices the chunk using the configured
	 * page/strip offset+limit. The returned bitmap contains exactly the PKs whose bodies will be the post-fetch
	 * winners — no over-fetch, no under-fetch: the pre-fetch slice matches the post-fetch sort+chunk in
	 * `EntityDecorator`. Each owner is sorted in its own scope - the key it is listed under in the owner map - so a
	 * link of an `inScope` container skips the owners of other scopes, as the decorator does.
	 *
	 * The references are filtered **row by row, before they are sorted and sliced**: a row the filter rejected, or
	 * a dropped one, may share its referenced primary key with an admitted row, and kept in the buffer it would take
	 * a slot of the page that the decorator empties afterwards - pushing an admitted row off the page. The groups
	 * recorded are those of the kept rows only.
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
	 *                                    before fetching. Row-aware comparators receive this unfiltered collection.
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
		int ownerCount = 0;
		for (int[] ownerKeys : this.entityPrimaryKey.values()) {
			ownerCount += ownerKeys.length;
		}
		this.groupsForEntity = CollectionUtils.createHashMap(ownerCount);
		final Formula[] slices = new Formula[ownerCount];
		int index = 0;
		for (Map.Entry<Scope, int[]> ownersInScope : this.entityPrimaryKey.entrySet()) {
			final Scope ownerScope = ownersInScope.getKey();
			for (int epk : ownersInScope.getValue()) {
				slices[index++] = sliceSortedOwner(
					epk, ownerScope, referencedEntityIds, validityMapping, referenceContractsAccessor, comparator
				);
			}
		}
		return FormulaFactory.or(slices).compute();
	}

	/**
	 * Filters and orders one owner's references and takes a page of rows before collecting their body keys. Records
	 * the groups of all admitted rows of the owner in {@link #groupsForEntity} as a side effect.
	 *
	 * @param ownerPrimaryKey primary key of the entity owning the references
	 * @param ownerScope scope of the entity owning the references
	 * @param referencedEntityIds filter-matching referenced entity keys
	 * @param validityMapping row admission mapping for each owner
	 * @param referenceContractsAccessor accessor for the owner's reference rows
	 * @param comparator head of the reference ordering chain
	 * @return formula for the referenced entity keys in the slice
	 */
	@Nonnull
	private Formula sliceSortedOwner(
		int ownerPrimaryKey,
		@Nonnull Scope ownerScope,
		@Nonnull Formula referencedEntityIds,
		@Nonnull ValidEntityToReferenceMapping validityMapping,
		@Nonnull BiFunction<String, Integer, Collection<ReferenceContract>> referenceContractsAccessor,
		@Nonnull ReferenceComparator comparator
	) {
		final Integer boxedOwnerPrimaryKey = ownerPrimaryKey;
		final Bitmap filteredReferenceEntityIds = FormulaFactory.and(
			this.referencedEntityIdsFormula.apply(this.referenceName, boxedOwnerPrimaryKey),
			referencedEntityIds,
			validityMapping.getValidReferencedEntitiesFormula(ownerPrimaryKey)
		).compute();
		if (filteredReferenceEntityIds.isEmpty()) {
			this.groupsForEntity.put(boxedOwnerPrimaryKey, ArrayUtils.EMPTY_INT_ARRAY);
			return EmptyFormula.INSTANCE;
		}
		final Collection<ReferenceContract> sourceRefs =
			referenceContractsAccessor.apply(this.referenceName, boxedOwnerPrimaryKey);
		final ReferenceContract[] filterBuffer = new ReferenceContract[sourceRefs.size()];
		int filteredCount = 0;
		for (ReferenceContract row : sourceRefs) {
			// A key admitted for this owner can also be shared by a rejected row.
			if (filteredReferenceEntityIds.contains(row.getReferencedPrimaryKey()) &&
				validityMapping.isReferenceSelected(ownerPrimaryKey, row)) {
				filterBuffer[filteredCount++] = row;
			}
		}
		final ReferenceContract[] filteredRefs = filteredCount == filterBuffer.length
			? filterBuffer
			: Arrays.copyOf(filterBuffer, filteredCount);
		// Group accounting covers all admitted rows, including those outside the requested page.
		this.groupsForEntity.put(boxedOwnerPrimaryKey, groupPksOf(filteredRefs));
		if (filteredRefs.length == 0) {
			return EmptyFormula.INSTANCE;
		}
		ReferenceComparator.sort(
			comparator, ownerPrimaryKey, ownerScope, filteredRefs, 0, filteredRefs.length, sourceRefs
		);
		final OffsetAndLimit offsetAndLimit = this.offsetAndLimitForSize.apply(filteredRefs.length);
		final int[] sliced = materializeSlicedPks(filteredRefs, offsetAndLimit);
		return sliced.length == 0 ? EmptyFormula.INSTANCE : ReferencedEntityFetcher.toFormula(sliced);
	}

	/**
	 * Returns the primary keys of the groups referenced by the supplied references in source order.
	 * References without a group are skipped. Used by the sorted slicing path to derive group
	 * accounting from the same `ReferenceContract` array that drives the slice.
	 */
	@Nonnull
	private static int[] groupPksOf(@Nonnull ReferenceContract[] refs) {
		final int[] buffer = new int[refs.length];
		int count = 0;
		for (ReferenceContract ref : refs) {
			final Optional<GroupEntityReference> group = ref.getGroup();
			if (group.isPresent()) {
				buffer[count++] = group.get().getPrimaryKeyOrThrowException();
			}
		}
		return count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
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
	 * Computes offset+limit for a {@link Strip} given the actual source size - shared by the bitmap
	 * {@link #slice(Bitmap, Strip)} and the sorted slice. It mirrors the post-fetch chunk of `StripTransformer`, which
	 * starts a strip whose offset lies past the last record at the first one; a slice that started anywhere else
	 * would fetch the bodies of other records than the decorator keeps.
	 *
	 * @param strip      requested strip (raw offset + limit)
	 * @param sourceSize size of the input collection being striped
	 * @return offset+limit of the strip - the offset reset to 0 when it lies at or past {@code sourceSize}
	 */
	@Nonnull
	private static OffsetAndLimit stripOffsetAndLimit(@Nonnull Strip strip, int sourceSize) {
		final int offset = strip.getOffset() >= sourceSize ? 0 : strip.getOffset();
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
	 * defined in the provided strip object. An offset past the bounds of the bitmap starts the subset at its first
	 * record, and a limit past them is truncated to fit within the bitmap size.
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
