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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.store.spi.chunk.PageTransformerWithSlicer;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * BitmapSlicer is supposed to identify only a small subset of referenced entities and their groups that should
 * be actually fetched / returned in the result taking `filterBy` and `page` / `strip` constraints into an account.
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
		} else if (chunkTransformer instanceof PageTransformerWithSlicer pageTransformerWithSlicer) {
			this.chunker = (bitmap) -> this.slice(
				bitmap, pageTransformerWithSlicer.getPage(), pageTransformerWithSlicer.getSlicer());
		} else if (chunkTransformer instanceof StripTransformer stripTransformer) {
			this.chunker = (bitmap) -> this.slice(bitmap, stripTransformer.getStrip());
		} else if (chunkTransformer instanceof NoTransformer) {
			this.chunker = Function.identity();
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
	 * Prepares the sliced information for all entity primary keys (no filtering is applied).
	 */
	public void sliceAllEntityIds() {
		this.groupsForEntity = CollectionUtils.createHashMap(this.entityPrimaryKey.size());
		for (int[] entityPrimaryKeys : this.entityPrimaryKey.values()) {
			for (int epk : entityPrimaryKeys) {
				final Bitmap referenceEntityIds = this.referencedEntityIdsFormula.apply(this.referenceName, epk)
					.compute();
				this.groupsForEntity.put(
					epk,
					referenceEntityIds.stream()
						.mapToObj(
							refId -> this.referencedEntityToGroupIdTranslator.apply(epk, this.referenceName, refId))
						.flatMapToInt(Function.identity())
						.toArray()
				);
			}
		}
	}


	/**
	 * Retrieves a Formula object that represents the group IDs associated with the given reference name
	 * and entity ID. This method obtains the group IDs from an internal mapping and converts them into
	 * a Formula for further processing or computation.
	 *
	 * When map doesn't contain the groups for the entityId, it is assumed the referenced entities don't have
	 * any group assigned.
	 *
	 * @param referenceName the name of the reference associated with the entity, must not be null
	 * @param entityId      the unique identifier of the entity for which group IDs are retrieved, must not be null
	 * @return a Formula object representing the group IDs associated with the specified reference name and entity ID
	 */
	@Nonnull
	public Formula getGroupIds(@Nonnull String referenceName, @Nonnull Integer entityId) {
		// slicer is always created only for a single reference, we need to be fast as possible, so no checks here
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
		final int pageNumber = page.getPageNumber();
		final int pageSize = page.getPageSize();
		final int realPageNumber = PaginatedList.isRequestedResultBehindLimit(
			pageNumber, pageSize, primaryKeys.size()) ?
			1 : pageNumber;
		final int offset = PaginatedList.getFirstItemNumberForPage(realPageNumber, pageSize);
		return primaryKeys.isEmpty() ?
			EmptyBitmap.INSTANCE :
			new ArrayBitmap(
				primaryKeys.getRange(
					offset,
					Math.min(offset + pageSize, primaryKeys.size())
				)
			);
	}

	/**
	 * Creates a subset of the provided bitmap by slicing it based on the specified page number and page size
	 * defined in the provided page object. If the page number or size exceeds the bounds of the bitmap,
	 * adjustments are made to fit within the bitmap size.
	 *
	 * @param primaryKeys the bitmap containing the full set of record IDs to be sliced
	 * @param page        the page object defining the page number and size for slicing the bitmap
	 * @param slicer      the slicer to calculate offset    and limit
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
		return primaryKeys.isEmpty() ?
			EmptyBitmap.INSTANCE :
			new ArrayBitmap(
				primaryKeys.getRange(
					Math.min(strip.getOffset(), primaryKeys.size() - 1),
					Math.min(strip.getOffset() + strip.getLimit(), primaryKeys.size())
				)
			);
	}

}
