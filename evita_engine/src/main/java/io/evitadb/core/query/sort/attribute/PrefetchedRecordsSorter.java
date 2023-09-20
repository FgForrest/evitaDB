/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This sorter requires list of pre-fetched entities in the {@link QueryContext}. If none is present the sorter is
 * skipped entirely. If pre-fetched entities are present they are sorted by a {@link #entityComparator} that uses
 * their data. This sorter avoids using pre-sorted indexes, because we speculate that the cardinality of the pre-fetched
 * entities is low and the sorting will be faster than using the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PrefetchedRecordsSorter extends AbstractRecordsSorter implements ConditionalSorter {
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;
	/**
	 * This instance will be used by this sorter in case the {@link QueryContext} contains list of prefetched entities.
	 */
	private final EntityComparator entityComparator;

	private PrefetchedRecordsSorter(EntityComparator entityComparator, Sorter unknownRecordIdsSorter) {
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
		this.entityComparator = entityComparator;
	}

	public PrefetchedRecordsSorter(EntityComparator entityComparator) {
		this.unknownRecordIdsSorter = null;
		this.entityComparator = entityComparator;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new PrefetchedRecordsSorter(
			entityComparator, sorterForUnknownRecords
		);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new PrefetchedRecordsSorter(
			entityComparator, null
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public boolean shouldApply(@Nonnull QueryContext queryContext) {
		return queryContext.getPrefetchedEntities() != null;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		final Bitmap selectedRecordIds = input.compute();
		final OfInt it = selectedRecordIds.iterator();
		final List<EntityContract> entities = new ArrayList<>(selectedRecordIds.size());
		while (it.hasNext()) {
			int id = it.next();
			entities.add(queryContext.translateToEntity(id));
		}

		entityComparator.prepareFor(endIndex - startIndex);
		entities.sort(entityComparator);

		int notFoundRecordsCnt = 0;
		final RoaringBitmap notFoundRecords = new RoaringBitmap();
		for (EntityContract entityContract : entityComparator.getNonSortedEntities()) {
			if (notFoundRecords.checkedAdd(queryContext.translateEntity(entityContract))) {
				notFoundRecordsCnt++;
			}
		}

		final AtomicInteger index = new AtomicInteger();
		final int[] result = new int[selectedRecordIds.size()];
		entities.subList(0, selectedRecordIds.size() - notFoundRecordsCnt)
			.stream()
			.skip(startIndex)
			.limit((long) endIndex - startIndex)
			.mapToInt(queryContext::translateEntity)
			.forEach(pk -> result[index.getAndIncrement()] = pk);

		return returnResultAppendingUnknown(
			queryContext,
			new SortResult(result, index.get()),
			notFoundRecords, unknownRecordIdsSorter,
			startIndex, endIndex
		);
	}

}
