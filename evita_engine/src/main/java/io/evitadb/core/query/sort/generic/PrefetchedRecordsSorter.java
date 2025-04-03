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
