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

package io.evitadb.core.query.sort.primaryKey.sorter;

import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Exact sorter outputs filtered results in an order defined by the order of input entity primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExactSorter implements Sorter {
	/**
	 * The entity primary keys whose order must be maintained in the sorted result.
	 */
	private final int[] exactOrder;

	public ExactSorter(@Nonnull int[] exactOrder) {
		this.exactOrder = exactOrder;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(@Nonnull SortingContext sortingContext, @Nonnull int[] result, @Nullable IntConsumer skippedRecordsConsumer) {
		final Bitmap filteredRecordIdBitmap = sortingContext.nonSortedKeys();
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();

		final int[] filteredRecordIds = filteredRecordIdBitmap.getArray();
		final int length = Math.min(filteredRecordIds.length, recomputedEndIndex - recomputedStartIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + recomputedStartIndex + ", Size: " + filteredRecordIds.length);
		}

		// sort the filtered entity primary keys along the exact order in input
		final int lastSortedItem = ArrayUtils.sortAlong(this.exactOrder, filteredRecordIds);

		// only the records matched by the exact order (positions `[0, lastSortedItem)`) are sorted by this sorter -
		// when the page starts past them, none is appended and only the matched ones count as skipped, so that
		// the next sorter skips the rest of the offset from the records it receives
		final int skippedRecords = Math.min(recomputedStartIndex, lastSortedItem);
		final int toAppend = Math.max(0, Math.min(lastSortedItem, recomputedEndIndex) - recomputedStartIndex);
		if (toAppend > 0) {
			System.arraycopy(filteredRecordIds, recomputedStartIndex, result, sortingContext.peak(), toAppend);
		}

		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < skippedRecords; i++) {
				skippedRecordsConsumer.accept(filteredRecordIds[i]);
			}
		}

		// if there are no more records to sort or no additional sorter is present, return entire result
		if (lastSortedItem == filteredRecordIdBitmap.size()) {
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				toAppend, skippedRecords
			);
		} else {
			// otherwise, collect the not sorted record ids
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (int i = lastSortedItem; i < filteredRecordIds.length; i++) {
				writer.add(filteredRecordIds[i]);
			}

			final PersistentRoaringBitmap outputBitmap = writer.get();
			return sortingContext.createResultContext(
				outputBitmap.isEmpty() ?
					EmptyBitmap.INSTANCE : new BaseBitmap(outputBitmap),
				toAppend, skippedRecords
			);
		}
	}

}
