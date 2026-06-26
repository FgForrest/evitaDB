/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort.generic;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * This sorter requires list of pre-fetched entities in the {@link QueryPlanningContext}. If none is present the sorter is
 * skipped entirely. If pre-fetched entities are present they are sorted by a {@link #entityComparator} that uses
 * their data. This sorter avoids using pre-sorted indexes, because we speculate that the cardinality of the pre-fetched
 * entities is low and the sorting will be faster than using the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PrefetchedRecordsSorter implements Sorter {
	/**
	 * This instance will be used by this sorter in case the {@link QueryPlanningContext} contains list of prefetched entities.
	 */
	private final EntityComparator entityComparator;

	public PrefetchedRecordsSorter(@Nonnull EntityComparator entityComparator) {
		this.entityComparator = entityComparator;
	}

	/**
	 * Sorts the candidate record set using the pre-fetched entity bodies and slices the result to the requested page.
	 *
	 * **Early-exit:** when {@link QueryExecutionContext#getPrefetchedEntities()} returns `null`, the sorter skips
	 * itself entirely and returns the incoming `sortingContext` unchanged so the next sorter in the chain can handle
	 * the full candidate set.
	 *
	 * **When prefetched entities are present:**
	 *
	 * 1. Translates each candidate record ID from `sortingContext.nonSortedKeys()` to its `EntityContract`
	 *    via `QueryExecutionContext.translateToEntity`.
	 * 2. Sorts the entity list with `entityComparator`. If the comparator implements
	 *    {@link EntityReferenceSensitiveComparator} and a `referenceKey` is set on the context, sorting is
	 *    scoped to that reference key.
	 * 3. Entities that the comparator could not sort (returned by `getNonSortedEntities()`) are collected into
	 *    a `notFoundRecords` bitmap and excluded from the sortable slice `[0, entitiesCount)`.
	 * 4. The sortable slice is paged and written into `result`; skipped records are reported to
	 *    `skippedRecordsConsumer` if provided.
	 * 5. Returns a new `SortingContext` carrying the unsortable records so the next sorter can place them.
	 *
	 * **Partition invariant:** every primary key reported by `entityComparator.getNonSortedEntities()` MUST
	 * fall outside the sortable slice. If the comparator places a non-sortable entity inside the slice, the
	 * sort/non-sort partitions disagree and the downstream sorter would emit duplicate primary keys.
	 * {@link Assert#isPremiseValid} throws immediately in that case to surface the violation early.
	 *
	 * @param sortingContext      the current sorting state including candidate keys and pagination window
	 * @param result              output array that receives sorted primary keys for the requested page slice
	 * @param skippedRecordsConsumer optional consumer called for each primary key skipped before the page window
	 * @return updated `SortingContext` with unsortable records passed to the next sorter in the chain
	 */
	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		if (queryContext.getPrefetchedEntities() == null) {
			return sortingContext;
		} else {
			final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
			final OfInt it = selectedRecordIds.iterator();
			final List<EntityContract> entities = new ArrayList<>(selectedRecordIds.size());
			while (it.hasNext()) {
				int id = it.next();
				entities.add(queryContext.translateToEntity(id));
			}

			final int recomputedStartIndex = sortingContext.recomputedStartIndex();
			final int recomputedEndIndex = sortingContext.recomputedEndIndex();
			final int peak = sortingContext.peak();

			this.entityComparator.prepareFor(recomputedEndIndex - recomputedStartIndex);

			if (this.entityComparator instanceof EntityReferenceSensitiveComparator ersc && sortingContext.referenceKey() != null) {
				ersc.withReferencedEntityId(
					sortingContext.referenceKey(),
					() -> entities.sort(this.entityComparator)
				);
			} else {
				entities.sort(this.entityComparator);
			}

			int notFoundRecordsCnt = 0;
			final RoaringBitmap notFoundRecords = new RoaringBitmap();
			for (EntityContract entityContract : this.entityComparator.getNonSortedEntities()) {
				if (notFoundRecords.checkedAdd(queryContext.translateEntity(entityContract))) {
					notFoundRecordsCnt++;
				}
			}

			final AtomicInteger index = new AtomicInteger();
			final int entitiesCount = selectedRecordIds.size() - notFoundRecordsCnt;
			final List<EntityContract> entityContracts = entities.subList(0, entitiesCount);
			// invariant: comparator must have pushed every non-sortable entity past `entitiesCount`;
			// if it did not, the trailing slice carries duplicates that leak into the next sorter.
			if (notFoundRecordsCnt > 0) {
				for (int i = 0; i < entitiesCount; i++) {
					final int pk = queryContext.translateEntity(entityContracts.get(i));
					Assert.isPremiseValid(
						!notFoundRecords.contains(pk),
						() -> "Entity comparator " + this.entityComparator.getClass().getName() +
							" reported entity #" + pk + " as non-sortable yet kept it inside" +
							" the sortable slice — sort/non-sort partitions disagree;" +
							" downstream sorters would emit duplicates."
					);
				}
			}
			final int skippedItems = Math.min(recomputedStartIndex, entitiesCount);
			final int appendedItems = Math.min(Math.min(recomputedEndIndex, entitiesCount), skippedItems + result.length - peak);
			if (skippedRecordsConsumer != null) {
				for (int i = 0; i < skippedItems; i++) {
					skippedRecordsConsumer.accept(queryContext.translateEntity(entityContracts.get(i)));
				}
			}
			for (int i = skippedItems; i < appendedItems; i++) {
				result[peak + index.getAndIncrement()] = queryContext.translateEntity(entityContracts.get(i));
			}

			final int[] buffer = queryContext.borrowBuffer();
			try {
				return sortingContext.createResultContext(
					notFoundRecords.isEmpty() ?
						EmptyBitmap.INSTANCE : new BaseBitmap(notFoundRecords),
					index.get(),
					skippedItems
				);
			} finally {
				queryContext.returnBuffer(buffer);
			}
		}
	}
}
